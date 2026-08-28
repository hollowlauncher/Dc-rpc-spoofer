#include "discord_rpc.h"
#include "discord_register.h"

extern "C" DISCORD_EXPORT void Discord_Register(const char* applicationId, const char* command)
{
    // No-op on Android: No desktop-style registry or custom URL scheme registration via files
}

extern "C" DISCORD_EXPORT void Discord_RegisterSteamGame(const char* applicationId, const char* steamId)
{
    // No-op on Android: Steam integration not applicable
}
