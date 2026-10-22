// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the misc/licenses/gplv2.txt file included.

#pragma once

#include "core/hle/service/service.h"

namespace Service::NWM {

enum class ConnectionType : u8 {
    Client = 0x1,
    Spectator = 0x2,
};

/*
 * Normalizes the connection type value received from an EAPoL-Start packet.
 * Some legacy implementations encode connection types differently, so this
 * function maps those values to the supported ConnectionType enum.
 */
static inline ConnectionType NormalizeConnectionType(u8 value) {
    switch (value) {
        case 0:
        case 1:
            return ConnectionType::Client;

        case 2:
            return ConnectionType::Spectator;

        default:
            return ConnectionType::Client;
    }
}

}; // namespace Service::NWM
