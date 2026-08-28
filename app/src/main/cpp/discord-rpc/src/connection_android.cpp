#include "connection.h"

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/un.h>
#include <unistd.h>
#include <stddef.h>

int GetProcessId()
{
    return ::getpid();
}

struct BaseConnectionUnix : public BaseConnection {
    int sock{-1};
};

static BaseConnectionUnix Connection;
static sockaddr_un PipeAddr{};
#ifdef MSG_NOSIGNAL
static int MsgFlags = MSG_NOSIGNAL;
#else
static int MsgFlags = 0;
#endif

/*static*/ BaseConnection* BaseConnection::Create()
{
    PipeAddr.sun_family = AF_UNIX;
    return &Connection;
}

/*static*/ void BaseConnection::Destroy(BaseConnection*& c)
{
    auto self = reinterpret_cast<BaseConnectionUnix*>(c);
    self->Close();
    c = nullptr;
}

bool BaseConnection::Open()
{
    auto self = reinterpret_cast<BaseConnectionUnix*>(this);
    self->sock = socket(AF_UNIX, SOCK_STREAM, 0);
    if (self->sock == -1) {
        return false;
    }
    fcntl(self->sock, F_SETFL, O_NONBLOCK);

    for (int pipeNum = 0; pipeNum < 10; ++pipeNum) {
        memset(&PipeAddr, 0, sizeof(PipeAddr));
        PipeAddr.sun_family = AF_UNIX;
        // Abstract namespace: first byte is null.
        // We skip the first byte of sun_path by writing to sun_path + 1.
        snprintf(PipeAddr.sun_path + 1, sizeof(PipeAddr.sun_path) - 1, "discord-ipc-%d", pipeNum);

        // In the abstract namespace, the address length is the number of bytes
        // used in sun_path, including the leading null.
        int pathLen = strlen(PipeAddr.sun_path + 1) + 1;
        int addrLen = offsetof(struct sockaddr_un, sun_path) + pathLen;

        int err = connect(self->sock, (const sockaddr*)&PipeAddr, addrLen);
        if (err == 0) {
            self->isOpen = true;
            return true;
        }
    }
    self->Close();
    return false;
}

bool BaseConnection::Close()
{
    auto self = reinterpret_cast<BaseConnectionUnix*>(this);
    if (self->sock == -1) {
        return false;
    }
    close(self->sock);
    self->sock = -1;
    self->isOpen = false;
    return true;
}

bool BaseConnection::Write(const void* data, size_t length)
{
    auto self = reinterpret_cast<BaseConnectionUnix*>(this);

    if (self->sock == -1) {
        return false;
    }

    ssize_t sentBytes = send(self->sock, data, length, MsgFlags);
    if (sentBytes < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK) {
            return false;
        }
        Close();
    }
    return sentBytes == (ssize_t)length;
}

bool BaseConnection::Read(void* data, size_t length)
{
    auto self = reinterpret_cast<BaseConnectionUnix*>(this);

    if (self->sock == -1) {
        return false;
    }

    ssize_t res = recv(self->sock, data, length, MsgFlags);
    if (res < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK) {
            return false;
        }
        Close();
    }
    else if (res == 0) {
        Close();
    }
    return res == (ssize_t)length;
}
