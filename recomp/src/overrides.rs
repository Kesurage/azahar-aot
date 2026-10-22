//! functions written by hand to run instead of the ones 3dsrecomp generates,
//! found in C files by the RECOMP_OVERRIDE marks recomp.h gives them.

use std::path::{Path, PathBuf};

/// one function written by hand.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Override {
    /// the module whose function it replaces, none for the executable.
    pub module: Option<String>,
    /// what it replaces, an address or an offset in the module, odd for
    /// Thumb.
    pub address: u32,
    /// the C function, as the mark names it.
    pub name: String,
    /// the name the generated function it replaces goes by for it.
    pub original: String,
}

/// a C file of overrides.
pub struct File {
    pub path: PathBuf,
    pub source: String,
    pub overrides: Vec<Override>,
}

/// the C files at path, a file or a directory of them.
pub fn load(path: &Path) -> Result<Vec<File>, String> {
    let paths = if path.is_dir() {
        let entries = std::fs::read_dir(path).map_err(|e| format!("could not read {}, {e}", path.display()))?;
        let mut paths: Vec<PathBuf> =
            entries.filter_map(|e| e.ok()).map(|e| e.path()).filter(|p| p.extension().is_some_and(|x| x == "c")).collect();
        paths.sort();
        paths
    } else {
        vec![path.to_owned()]
    };
    paths
        .into_iter()
        .map(|path| {
            let source = std::fs::read_to_string(&path).map_err(|e| format!("could not read {}, {e}", path.display()))?;
            let overrides = parse(&source).map_err(|e| format!("{}, {e}", path.display()))?;
            Ok(File { path, source, overrides })
        })
        .collect()
}

/// the overrides a C source marks.
pub fn parse(source: &str) -> Result<Vec<Override>, String> {
    let code = strip_directives(&strip_comments(source));
    let mut overrides = Vec::new();
    let mut rest = code.as_str();
    while let Some(at) = rest.find("RECOMP_OVERRIDE") {
        let before = rest[..at].chars().next_back();
        rest = &rest[at + "RECOMP_OVERRIDE".len()..];
        if before.is_some_and(is_identifier) {
            continue;
        }
        let (in_module, after) = match rest.strip_prefix("_IN") {
            Some(after) => (true, after),
            None => (false, rest),
        };
        let Some(after) = after.trim_start().strip_prefix('(') else { continue };
        let Some(end) = after.find(')') else { return Err("a RECOMP_OVERRIDE is not closed".to_owned()) };
        let arguments: Vec<&str> = after[..end].split(',').map(str::trim).collect();
        rest = &after[end..];
        let item = match (in_module, arguments.as_slice()) {
            (false, [literal]) => Override {
                module: None,
                address: number(literal)?,
                name: format!("override_{literal}"),
                original: format!("original_{literal}"),
            },
            (true, [module, literal]) => {
                if module.is_empty() || !module.chars().all(is_identifier) {
                    return Err(format!("{module} is not a module name"));
                }
                Override {
                    module: Some((*module).to_owned()),
                    address: number(literal)?,
                    name: format!("override_{module}_{literal}"),
                    original: format!("original_{module}_{literal}"),
                }
            }
            (true, _) => return Err("RECOMP_OVERRIDE_IN takes a module and an offset".to_owned()),
            (false, _) => return Err("RECOMP_OVERRIDE takes an address".to_owned()),
        };
        if overrides.iter().any(|o: &Override| o.module == item.module && o.address == item.address) {
            return Err(format!("0x{:08X} is overridden twice", item.address));
        }
        overrides.push(item);
    }
    Ok(overrides)
}

/// the instructions a C source marks with RECOMP_TUNABLE(address, symbol): the address of an
/// instruction in the executable whose immediate operand the generated code reads from the C
/// variable `symbol` (defined by hand, holding the immediate it replaces) so that the host can
/// change it while the title runs.
pub fn tunables(source: &str) -> Result<Vec<(u32, String)>, String> {
    let code = strip_directives(&strip_comments(source));
    let mut found: Vec<(u32, String)> = Vec::new();
    let mut rest = code.as_str();
    while let Some(at) = rest.find("RECOMP_TUNABLE") {
        let before = rest[..at].chars().next_back();
        rest = &rest[at + "RECOMP_TUNABLE".len()..];
        if before.is_some_and(is_identifier) || rest.chars().next().is_some_and(is_identifier) {
            continue;
        }
        let Some(after) = rest.trim_start().strip_prefix('(') else { continue };
        let Some(end) = after.find(')') else { return Err("a RECOMP_TUNABLE is not closed".to_owned()) };
        let arguments: Vec<&str> = after[..end].split(',').map(str::trim).collect();
        rest = &after[end..];
        let [literal, symbol] = arguments.as_slice() else {
            return Err("RECOMP_TUNABLE takes an address and a variable".to_owned());
        };
        if symbol.is_empty() || !symbol.chars().all(is_identifier) {
            return Err(format!("{symbol} is not a variable name"));
        }
        let address = number(literal)?;
        if found.iter().any(|(a, _)| *a == address) {
            return Err(format!("0x{address:08X} is marked tunable twice"));
        }
        found.push((address, (*symbol).to_owned()));
    }
    Ok(found)
}

fn is_identifier(c: char) -> bool {
    c.is_ascii_alphanumeric() || c == '_'
}

/// a literal as C writes it, which also has to fit in a name.
fn number(literal: &str) -> Result<u32, String> {
    let parsed = match literal.strip_prefix("0x").or_else(|| literal.strip_prefix("0X")) {
        Some(hex) => u32::from_str_radix(hex, 16),
        None => literal.parse(),
    };
    match parsed {
        Ok(value) if literal.chars().all(is_identifier) => Ok(value),
        _ => Err(format!("{literal} is not an address")),
    }
}

/// the source with its preprocessor lines (`#define` and its `\\` continuations, `#include`,
/// ...) blanked out. A mark inside a macro's body names no address, and the override a
/// macro would write can't be found by name anyway, so a mark is only looked for in code.
fn strip_directives(source: &str) -> String {
    let mut out = String::with_capacity(source.len());
    let mut in_directive = false;
    for line in source.split_inclusive('\n') {
        let body = line.trim_end_matches(['\n', '\r']);
        if in_directive || body.trim_start().starts_with('#') {
            in_directive = body.ends_with('\\');
            out.push('\n');
        } else {
            out.push_str(line);
        }
    }
    out
}

/// the source with its comments and the insides of its strings blanked
/// out.
fn strip_comments(source: &str) -> String {
    let mut out = String::with_capacity(source.len());
    let mut chars = source.chars().peekable();
    while let Some(c) = chars.next() {
        match c {
            '/' if chars.peek() == Some(&'/') => {
                for c in chars.by_ref() {
                    if c == '\n' {
                        out.push('\n');
                        break;
                    }
                }
            }
            '/' if chars.peek() == Some(&'*') => {
                chars.next();
                let mut last = ' ';
                for c in chars.by_ref() {
                    if last == '*' && c == '/' {
                        break;
                    }
                    last = c;
                }
                out.push(' ');
            }
            '"' | '\'' => {
                out.push(c);
                while let Some(inner) = chars.next() {
                    if inner == '\\' {
                        chars.next();
                        out.push_str("  ");
                    } else if inner == c {
                        out.push(c);
                        break;
                    } else {
                        out.push(' ');
                    }
                }
            }
            _ => out.push(c),
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn marks_are_found_outside_comments() {
        let source = r#"
            #include "overrides.h"
            /* RECOMP_OVERRIDE(0x1000) is only an example */
            // RECOMP_OVERRIDE(0x2000) too
            const char *text = "RECOMP_OVERRIDE(0x3000)";
            RECOMP_OVERRIDE(0x0012A4F1) {
                RETURN_TO(ctx->r[14]);
            }
            RECOMP_OVERRIDE_IN(DllField, 0x40) {
                CALL(RECOMP_ORIGINAL_IN(DllField, 0x40));
            }
        "#;
        let overrides = parse(source).unwrap();
        assert_eq!(
            overrides,
            [
                Override {
                    module: None,
                    address: 0x0012_A4F1,
                    name: "override_0x0012A4F1".to_owned(),
                    original: "original_0x0012A4F1".to_owned(),
                },
                Override {
                    module: Some("DllField".to_owned()),
                    address: 0x40,
                    name: "override_DllField_0x40".to_owned(),
                    original: "original_DllField_0x40".to_owned(),
                },
            ]
        );
    }

    #[test]
    fn tunables_are_found_in_code_only() {
        let source = "#define RECOMP_TUNABLE(address, symbol)\nuint32_t rows = 400;\n/* RECOMP_TUNABLE(0x1, no) */\nRECOMP_TUNABLE(0x005de49c, rows)\nRECOMP_TUNABLE(0x00af0c84, rows)\n";
        assert_eq!(tunables(source).unwrap(), vec![(0x005de49c, "rows".to_owned()), (0x00af0c84, "rows".to_owned())]);
        assert!(tunables("RECOMP_TUNABLE(0x10, a)\nRECOMP_TUNABLE(0x10, b)").is_err());
        assert!(tunables("RECOMP_TUNABLE(address, rows)").is_err());
    }

    #[test]
    fn marks_inside_macros_are_not_marks() {
        let source = "#define SKIP(address) \\\n    RECOMP_OVERRIDE(address) { \\\n        RETURN_TO(ctx->r[14]); \\\n    }\nRECOMP_OVERRIDE(0x1000) { RETURN_TO(ctx->r[14]); }\n";
        let overrides = parse(source).unwrap();
        assert_eq!(overrides.len(), 1);
        assert_eq!(overrides[0].address, 0x1000);
    }

    #[test]
    fn bad_marks_are_reported() {
        assert!(parse("RECOMP_OVERRIDE(main) {}").is_err());
        assert!(parse("RECOMP_OVERRIDE(0x10) {} RECOMP_OVERRIDE(16) {}").is_err());
        assert!(parse("RECOMP_OVERRIDE_IN(0x10) {}").is_err());
    }
}
