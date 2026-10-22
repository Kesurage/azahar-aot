// Copyright 2022 Citra Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the misc/licenses/gplv2.txt file included.

#include <algorithm>
#include <utility>
#include <vector>

#include <jni.h>

#include "core/cheats/cheat_base.h"
#include <string>
#include <fmt/format.h>
#include "jni/android_common/android_common.h"
#include "common/file_util.h"
#include "core/cheats/cheats.h"
#include "core/cheats/gateway_cheat.h"
#include "core/core.h"
#include "jni/cheats/cheat.h"
#include "jni/id_cache.h"

extern "C" {

static Cheats::CheatEngine& GetEngine() {
    Core::System& system{Core::System::GetInstance()};
    return system.CheatEngine();
}

static std::string CheatFilePath(jlong title_id) {
    return fmt::format("{}{:016X}.txt", FileUtil::GetUserPath(FileUtil::UserPath::CheatsDir),
                       static_cast<u64>(title_id));
}

JNIEXPORT void JNICALL
Java_org_citra_citra_1emu_features_cheats_model_CheatEngine_setBuiltInCheatsEnabled(
    JNIEnv* env, jclass, jboolean enabled) {
    const bool value = enabled == JNI_TRUE;
    if (Cheats::GatewayCheat::IsBuiltInEnabled() != value) {
        Cheats::GatewayCheat::SetBuiltInEnabled(value);
        GetEngine().InvalidateLoaded();
    }
}

JNIEXPORT jstring JNICALL Java_org_citra_citra_1emu_features_cheats_model_CheatEngine_getCheatFileText(
    JNIEnv* env, jclass, jlong title_id) {
    std::string text;
    const std::string path = CheatFilePath(title_id);
    if (FileUtil::Exists(path)) {
        FileUtil::ReadFileToString(true, path, text);
    }
    return ToJString(env, text);
}

JNIEXPORT void JNICALL Java_org_citra_citra_1emu_features_cheats_model_CheatEngine_setCheatFileText(
    JNIEnv* env, jclass, jlong title_id, jstring j_text) {
    std::string text = GetJString(env, j_text);
    text.erase(std::remove(text.begin(), text.end(), '\r'), text.end());
    const auto first = text.find_first_not_of(" \t\n");
    if (first != std::string::npos && text[first] != '[') {
        text = "[Cheat]\n" + text;
    }
    const std::string dir = FileUtil::GetUserPath(FileUtil::UserPath::CheatsDir);
    if (!FileUtil::IsDirectory(dir)) {
        FileUtil::CreateDir(dir);
    }
    FileUtil::WriteStringToFile(true, CheatFilePath(title_id), text);
    GetEngine().InvalidateLoaded();
    GetEngine().LoadCheatFile(title_id);
}

JNIEXPORT void JNICALL Java_org_citra_citra_1emu_features_cheats_model_CheatEngine_loadCheatFile(
    JNIEnv* env, jclass, jlong title_id) {
    GetEngine().LoadCheatFile(title_id);
}

JNIEXPORT void JNICALL Java_org_citra_citra_1emu_features_cheats_model_CheatEngine_saveCheatFile(
    JNIEnv* env, jclass, jlong title_id) {
    GetEngine().SaveCheatFile(title_id);
}

JNIEXPORT jobjectArray JNICALL
Java_org_citra_citra_1emu_features_cheats_model_CheatEngine_getCheats(JNIEnv* env, jclass) {
    auto cheats = GetEngine().GetCheats();

    const jobjectArray array =
        env->NewObjectArray(static_cast<jsize>(cheats.size()), IDCache::GetCheatClass(), nullptr);

    jsize i = 0;
    for (auto& cheat : cheats)
        env->SetObjectArrayElement(array, i++, CheatToJava(env, std::move(cheat)));

    return array;
}

JNIEXPORT void JNICALL Java_org_citra_citra_1emu_features_cheats_model_CheatEngine_addCheat(
    JNIEnv* env, jclass, jobject j_cheat) {
    auto cheat = *CheatFromJava(env, j_cheat);
    GetEngine().AddCheat(std::move(cheat));
}

JNIEXPORT void JNICALL Java_org_citra_citra_1emu_features_cheats_model_CheatEngine_removeCheat(
    JNIEnv* env, jclass, jint index) {
    GetEngine().RemoveCheat(index);
}

JNIEXPORT void JNICALL Java_org_citra_citra_1emu_features_cheats_model_CheatEngine_updateCheat(
    JNIEnv* env, jclass, jint index, jobject j_new_cheat) {
    auto cheat = *CheatFromJava(env, j_new_cheat);
    GetEngine().UpdateCheat(index, std::move(cheat));
}
}
