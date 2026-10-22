//! loops that change nothing. a thread alone on the core polling memory that
//! nothing else changes before the next event only spins until then, which
//! costs a console's core nothing and the interpreter every turn. once a
//! turn of a loop is seen to leave the core and every byte it wrote as they
//! were, with no call to the kernel and no event in between, the turns after
//! it would do the same until the next event, and the run skips to it.

use zakuro_cpu::Snapshot;

use crate::kernel::thread::ThreadId;
use crate::System;

/// a thread that ran this long without calling the kernel is watched.
const QUIET: u64 = 2048;
/// a turn longer than this is not a polling loop's.
const LONGEST_TURN: u32 = 4096;
/// after a watch found no such loop, the next one waits this long at first,
/// twice as long each time it finds none again, up to the most.
const RETRY: u64 = 65_536;
const MOST_RETRY: u64 = 2 * 1024 * 1024;
/// while other threads could run, how long until looking again.
const SHARED: u64 = 4096;
/// below the stack pointer, what calls pushed and popped again, which
/// nothing reads.
const STACK_SCRATCH: u32 = 0x800;

pub struct IdleLoops {
    watch: Option<Watch>,
    /// before this cycle no watch starts, a step pays one comparison.
    next_check: u64,
    /// how long the last wait after a watch that found nothing was.
    retry: u64,
    /// cycles skipped, for reports.
    pub skipped: u64,
}

impl IdleLoops {
    /// loops skipped past or, when off, never looked for.
    pub fn new(on: bool) -> IdleLoops {
        IdleLoops { watch: None, next_check: if on { 0 } else { u64::MAX }, retry: 0, skipped: 0 }
    }

    /// whether a step has anything to look at, a watch going on or one due.
    #[inline(always)]
    pub(crate) fn due(&self, now: u64) -> bool {
        now >= self.next_check || self.watch.is_some()
    }
}

/// a loop watched from a point in it, with the core as it was there.
struct Watch {
    thread: ThreadId,
    pc: u32,
    core: Snapshot,
    steps: u32,
    /// the point was come back to once, and the core taken again there. the
    /// first turn of a loop starts from what was before it, the ones after
    /// from what a turn leaves.
    again: bool,
}

impl System {
    /// where the run skips to, when a thread alone on the core spins in a
    /// loop that changes nothing and has nothing to do until then.
    pub(crate) fn idle_until(&mut self, deadline: Option<u64>) -> Option<u64> {
        let thread = self.kernel.current_thread?;
        let now = self.cpu.cycles;
        let Some(watch) = &mut self.idle.watch else {
            if self.kernel.others_runnable() {
                self.idle.next_check = now + SHARED;
            } else {
                self.idle.watch = Some(Watch { thread, pc: self.cpu.regs[15], core: self.cpu.snapshot(), steps: 0, again: false });
                self.memory.log_writes();
            }
            return None;
        };
        watch.steps += 1;
        if watch.thread != thread || watch.steps > LONGEST_TURN {
            self.give_up_watching();
            return None;
        }
        if self.cpu.regs[15] != watch.pc {
            return None;
        }
        let core = self.cpu.snapshot();
        if core != watch.core {
            if !watch.again {
                watch.again = true;
                watch.core = core;
                self.memory.log_writes();
            }
            return None;
        }
        let sp = self.cpu.regs[13];
        if !self.memory.writes_undone(sp.saturating_sub(STACK_SCRATCH)..sp) {
            return None;
        }
        // a turn that changed nothing, the ones after it would not either
        self.idle.watch = None;
        self.memory.stop_logging_writes();
        self.idle.retry = 0;
        self.idle.next_check = now;
        if self.kernel.others_runnable() {
            return None;
        }
        let mut until = self.next_frame_boundary.min(self.next_audio_frame);
        if let Some(deadline) = deadline {
            until = until.min(deadline);
        }
        if let Some(event) = self.kernel.next_event() {
            until = until.min(event);
        }
        if until <= now {
            self.kernel.reschedule_pending = true;
            return None;
        }
        self.idle.skipped += until - now;
        Some(until)
    }

    /// a watch that found no such loop, the next one waits longer.
    fn give_up_watching(&mut self) {
        self.idle.watch = None;
        self.memory.stop_logging_writes();
        self.idle.retry = (self.idle.retry * 2).clamp(RETRY, MOST_RETRY);
        self.idle.next_check = self.cpu.cycles + self.idle.retry;
    }

    /// something besides the loop happened, a call to the kernel, an event,
    /// code that ran recompiled or another thread getting the core, which a
    /// watched turn must not span. busy when the running thread did it.
    pub(crate) fn idle_interrupted(&mut self, busy: bool) {
        if self.idle.watch.take().is_some() {
            self.memory.stop_logging_writes();
        }
        if busy {
            self.idle.next_check = self.idle.next_check.max(self.cpu.cycles + QUIET);
        }
    }
}
