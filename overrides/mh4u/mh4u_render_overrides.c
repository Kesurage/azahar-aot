/* Intentionally empty.

   The render options this file used to compile in (stereo, dither, anti-aliasing, the
   screen filters) are runtime options of the port's hooks now (overrides/mh4u/mh4u_hooks.c,
   switched in the app's settings), and a second override of the same address would not build.
   The file stays so a build that names it still works; build with the whole folder,
   --overrides overrides/mh4u. */
#include "overrides.h"
