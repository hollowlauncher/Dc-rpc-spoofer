cmake_minimum_required(VERSION 3.22.1)
project(discord-rpc)

set(CMAKE_CXX_STANDARD 14)

# We use DISCORD_LINUX as a base for Android
add_definitions(-DDISCORD_LINUX)
add_definitions(-DDISCORD_DYNAMIC_LIB)
add_definitions(-DDISCORD_BUILDING_SDK)
add_definitions(-DDISCORD_DISABLE_IO_THREAD)

include_directories(
    discord-rpc/include
    thirdparty/rapidjson/include
)

set(DISCORD_RPC_SRC
    discord-rpc/src/discord_rpc.cpp
    discord-rpc/src/rpc_connection.cpp
    discord-rpc/src/serialization.cpp
    discord-rpc/src/connection_android.cpp
    discord-rpc/src/discord_register_android.cpp
)

add_library(discord-rpc SHARED ${DISCORD_RPC_SRC})

# Hide all symbols by default.
# Functions marked with DISCORD_EXPORT in discord_rpc.h will be visible.
target_compile_options(discord-rpc PRIVATE -fvisibility=hidden)

# Linker flags for 16KB page alignment (Android 15+ requirement)
target_link_options(discord-rpc PRIVATE "-Wl,-z,max-page-size=16384")

# Standard Android libraries
target_link_libraries(discord-rpc log android)
