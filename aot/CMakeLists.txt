# azahar_aot: Azahar CPU backend running 3dsrecomp's ahead-of-time compiled code.
# Used from Azahar's tree: cmake -DAOT_DIR=<azahar-aot>/aot (see patches/ and scripts/).
add_library(azahar_aot STATIC
    src/aot_library.cpp
    src/aot_library.h
    src/arm_aot.cpp
    src/arm_aot.h
)
# recomp.h (3dsrecomp's interface, ABI 4) is private: it defines short macros (C_EQ, LIKELY...)
# that must not leak into Azahar's headers.
target_include_directories(azahar_aot PUBLIC src PRIVATE include)
target_compile_definitions(azahar_aot PRIVATE RECOMP_HOST_SVC)
target_link_libraries(azahar_aot PUBLIC citra_core citra_common ${CMAKE_DL_LIBS})
# arm_dynarmic.h (the fallback CPU) includes <dynarmic/...>; citra_core links dynarmic PRIVATE
if (TARGET dynarmic)
    target_link_libraries(azahar_aot PRIVATE dynarmic)
endif()
set_target_properties(azahar_aot PROPERTIES POSITION_INDEPENDENT_CODE ON)
