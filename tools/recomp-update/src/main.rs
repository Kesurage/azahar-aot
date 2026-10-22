//! recomp-update <game> <update> <outdir> [--overrides <file or dir>]
//!
//! 3dsrecomp's own command line only reads a game image, and refuses an update. Zakuro's
//! Recompile button recompiles the game "the way its update leaves it": it attaches the update
//! to the game, takes the code, exheader and CRO modules the update replaces, and hands them to
//! 3dsrecomp's `build` as `Mods`. This does the same without the GUI (logic from
//! zakuro/crates/zakuro/src/recompile.rs and zakuro-core/src/loader.rs, MIT).
//! The library lands in <outdir> as <TITLEID>.so, compiled with $CC.

use std::path::{Path, PathBuf};
use std::process::exit;

use recomp3ds::build::{self, Event};
use recomp3ds::Mods;

fn is_module(path: &str) -> bool {
    let path = path.to_ascii_lowercase();
    path.ends_with(".cro") || path == "static.crs"
}

/// the modules of the game an update has a version of, by RomFS path, with that version's bytes.
fn update_modules(update: &zakuro_fs::Title, game: &zakuro_fs::RomFs) -> Vec<(String, Vec<u8>)> {
    let Some(romfs) = &update.romfs else { return Vec::new() };
    let mut modules = Vec::new();
    let Ok(root) = romfs.root() else { return modules };
    let mut pending = vec![(String::new(), root)];
    while let Some((path, dir)) = pending.pop() {
        for (_, file) in romfs.files(&dir) {
            let path = if path.is_empty() { file.name.clone() } else { format!("{path}/{}", file.name) };
            if !is_module(&path) || game.lookup(&path).is_err() {
                continue;
            }
            if let Some(bytes) = update.read_romfs(&file, 0, file.data_size as usize).map(<[u8]>::to_vec) {
                modules.push((path, bytes));
            }
        }
        for (_, child) in romfs.subdirs(&dir) {
            pending.push((if path.is_empty() { child.name.clone() } else { format!("{path}/{}", child.name) }, child));
        }
    }
    modules
}

fn fail(message: impl std::fmt::Display) -> ! {
    eprintln!("recomp-update: {message}");
    exit(1);
}

fn main() {
    let mut words = Vec::new();
    let mut overrides: Option<PathBuf> = None;
    let mut args = std::env::args().skip(1);
    while let Some(arg) = args.next() {
        if arg == "--overrides" {
            overrides = Some(args.next().unwrap_or_else(|| fail("--overrides needs a path")).into());
        } else {
            words.push(arg);
        }
    }
    let [game, update, out] = &words[..] else {
        fail("usage: recomp-update <game> <update> <outdir> [--overrides <file or dir>]");
    };

    let mut title = zakuro_fs::Title::load(Path::new(game)).unwrap_or_else(|e| fail(format!("{game}: {e}")));
    let upd = zakuro_fs::Update::load(Path::new(update)).unwrap_or_else(|e| fail(format!("{update}: {e}")));
    if upd.title_id & 0xFFFF_FFFF != title.program_id() & 0xFFFF_FFFF {
        fail(format!("{update} updates {:016X}, not {:016X}", upd.title_id, title.program_id()));
    }
    println!("update to v{} attached to {:016X}", zakuro_fs::version_name(upd.version), title.program_id());
    title.attach_update(upd);

    let code = title.code().unwrap_or_else(|e| fail(format!("the code can't be read: {e}")));
    let exheader = title.exheader_bytes().to_vec();
    let modules = match (title.update(), &title.romfs) {
        (Some(update), Some(game)) => update_modules(&update.title, game),
        _ => Vec::new(),
    };
    println!("code {} bytes, {} CRO modules replaced by the update", code.len(), modules.len());
    let files = |path: &str| {
        modules.iter().find(|(at, _)| at.eq_ignore_ascii_case(path)).map(|(_, bytes)| bytes.clone())
    };
    std::fs::create_dir_all(out).unwrap_or_else(|e| fail(format!("{out}: {e}")));
    let options = build::Options {
        dir: Some(Path::new(out)),
        overrides: overrides.as_deref(),
        mods: Mods { code: Some(&code), exheader: Some(&exheader), romfs: Some(&files) },
        ..build::Options::default()
    };
    let events = |event: Event| match event {
        Event::Generated { files, bytes, overrides } => println!("generated {files} files, {bytes} bytes, {overrides} overrides"),
        Event::Note(note) => println!("note: {note}"),
        Event::Compiled { done, total } => {
            if done % 20 == 0 || done == total {
                println!("compiled {done}/{total}");
            }
        }
        Event::Built { library, took } => println!("built {} in {took:?}", library.display()),
        Event::Installed(path) => println!("installed {}", path.display()),
    };
    match build::build(Path::new(game), &options, &events) {
        Ok(path) => println!("done: {}", path.display()),
        Err(error) => fail(error),
    }
}
