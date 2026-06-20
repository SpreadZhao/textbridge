#include <fcitx-utils/event.h>
#include <fcitx-utils/eventloopinterface.h>
#include <fcitx-utils/capabilityflags.h>
#include <fcitx-utils/log.h>
#include <fcitx/addonfactory.h>
#include <fcitx/addoninstance.h>
#include <fcitx/addonmanager.h>
#include <fcitx/inputcontext.h>
#include <fcitx/instance.h>

#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <unistd.h>

#include <cerrno>
#include <cstdio>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace {

constexpr std::size_t kMaxTextBytes = 16 * 1024;
constexpr std::size_t kMaxDatagramBytes = kMaxTextBytes + 4096;

struct Request {
    int version = 0;
    std::string id;
    std::string action;
    std::string text;
    std::string key;
    std::vector<std::string> modifiers;
};

struct CommitResult {
    std::string status;
    std::string targetProgram;
};

void appendUtf8(std::string &out, uint32_t cp) {
    if (cp <= 0x7F) {
        out.push_back(static_cast<char>(cp));
    } else if (cp <= 0x7FF) {
        out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else if (cp <= 0xFFFF) {
        out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else {
        out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    }
}

int hexValue(char c) {
    if (c >= '0' && c <= '9') {
        return c - '0';
    }
    if (c >= 'a' && c <= 'f') {
        return c - 'a' + 10;
    }
    if (c >= 'A' && c <= 'F') {
        return c - 'A' + 10;
    }
    return -1;
}

bool isUuidString(const std::string &value) {
    if (value.size() != 36) {
        return false;
    }
    for (std::size_t i = 0; i < value.size(); ++i) {
        if (i == 8 || i == 13 || i == 18 || i == 23) {
            if (value[i] != '-') {
                return false;
            }
        } else if (hexValue(value[i]) < 0) {
            return false;
        }
    }
    return true;
}

class JsonReader {
public:
    explicit JsonReader(std::string_view input) : input_(input) {}

    std::optional<Request> parseRequest() {
        Request request;
        skipWs();
        if (!consume('{')) {
            return std::nullopt;
        }

        skipWs();
        if (consume('}')) {
            return std::nullopt;
        }

        while (true) {
            auto key = parseString();
            if (!key) {
                return std::nullopt;
            }
            skipWs();
            if (!consume(':')) {
                return std::nullopt;
            }
            skipWs();

            if (*key == "v") {
                auto number = parseInteger();
                if (!number) {
                    return std::nullopt;
                }
                request.version = *number;
            } else if (*key == "id") {
                auto value = parseString();
                if (!value) {
                    return std::nullopt;
                }
                request.id = *value;
            } else if (*key == "action") {
                auto value = parseString();
                if (!value) {
                    return std::nullopt;
                }
                request.action = *value;
            } else if (*key == "text") {
                auto value = parseString();
                if (!value) {
                    return std::nullopt;
                }
                request.text = *value;
            } else if (*key == "key") {
                auto value = parseString();
                if (!value) {
                    return std::nullopt;
                }
                request.key = *value;
            } else if (*key == "modifiers") {
                auto values = parseStringArray();
                if (!values) {
                    return std::nullopt;
                }
                request.modifiers = *values;
            } else if (!skipValue()) {
                return std::nullopt;
            }

            skipWs();
            if (consume('}')) {
                break;
            }
            if (!consume(',')) {
                return std::nullopt;
            }
            skipWs();
        }

        skipWs();
        if (pos_ != input_.size()) {
            return std::nullopt;
        }
        if (request.version != 1 || !isUuidString(request.id)) {
            return std::nullopt;
        }
        if (!request.action.empty() && request.action != "key") {
            return std::nullopt;
        }
        if (request.action == "key" && request.key.empty()) {
            return std::nullopt;
        }
        return request;
    }

private:
    void skipWs() {
        while (pos_ < input_.size()) {
            char c = input_[pos_];
            if (c != ' ' && c != '\n' && c != '\r' && c != '\t') {
                break;
            }
            ++pos_;
        }
    }

    bool consume(char expected) {
        if (pos_ >= input_.size() || input_[pos_] != expected) {
            return false;
        }
        ++pos_;
        return true;
    }

    std::optional<int> parseInteger() {
        if (pos_ >= input_.size() || input_[pos_] < '0' || input_[pos_] > '9') {
            return std::nullopt;
        }
        int value = 0;
        while (pos_ < input_.size() && input_[pos_] >= '0' && input_[pos_] <= '9') {
            value = value * 10 + (input_[pos_] - '0');
            ++pos_;
        }
        return value;
    }

    std::optional<uint32_t> parseHex4() {
        if (pos_ + 4 > input_.size()) {
            return std::nullopt;
        }
        uint32_t value = 0;
        for (int i = 0; i < 4; ++i) {
            int part = hexValue(input_[pos_ + i]);
            if (part < 0) {
                return std::nullopt;
            }
            value = (value << 4) | static_cast<uint32_t>(part);
        }
        pos_ += 4;
        return value;
    }

    std::optional<std::string> parseString() {
        if (!consume('"')) {
            return std::nullopt;
        }

        std::string value;
        while (pos_ < input_.size()) {
            char c = input_[pos_++];
            if (c == '"') {
                return value;
            }
            if (static_cast<unsigned char>(c) < 0x20) {
                return std::nullopt;
            }
            if (c != '\\') {
                value.push_back(c);
                continue;
            }
            if (pos_ >= input_.size()) {
                return std::nullopt;
            }
            char esc = input_[pos_++];
            switch (esc) {
            case '"':
            case '\\':
            case '/':
                value.push_back(esc);
                break;
            case 'b':
                value.push_back('\b');
                break;
            case 'f':
                value.push_back('\f');
                break;
            case 'n':
                value.push_back('\n');
                break;
            case 'r':
                value.push_back('\r');
                break;
            case 't':
                value.push_back('\t');
                break;
            case 'u': {
                auto cp = parseHex4();
                if (!cp) {
                    return std::nullopt;
                }
                if (*cp >= 0xD800 && *cp <= 0xDBFF) {
                    if (pos_ + 2 > input_.size() || input_[pos_] != '\\' || input_[pos_ + 1] != 'u') {
                        return std::nullopt;
                    }
                    pos_ += 2;
                    auto low = parseHex4();
                    if (!low || *low < 0xDC00 || *low > 0xDFFF) {
                        return std::nullopt;
                    }
                    *cp = 0x10000 + (((*cp - 0xD800) << 10) | (*low - 0xDC00));
                } else if (*cp >= 0xDC00 && *cp <= 0xDFFF) {
                    return std::nullopt;
                }
                appendUtf8(value, *cp);
                break;
            }
            default:
                return std::nullopt;
            }
        }

        return std::nullopt;
    }

    std::optional<std::vector<std::string>> parseStringArray() {
        std::vector<std::string> values;
        if (!consume('[')) {
            return std::nullopt;
        }
        skipWs();
        if (consume(']')) {
            return values;
        }

        while (true) {
            auto value = parseString();
            if (!value) {
                return std::nullopt;
            }
            values.push_back(*value);

            skipWs();
            if (consume(']')) {
                return values;
            }
            if (!consume(',')) {
                return std::nullopt;
            }
            skipWs();
        }
    }

    bool skipValue() {
        skipWs();
        if (pos_ >= input_.size()) {
            return false;
        }
        if (input_[pos_] == '"') {
            return parseString().has_value();
        }
        if (input_[pos_] == '{') {
            ++pos_;
            skipWs();
            if (consume('}')) {
                return true;
            }
            while (true) {
                if (!parseString()) {
                    return false;
                }
                skipWs();
                if (!consume(':') || !skipValue()) {
                    return false;
                }
                skipWs();
                if (consume('}')) {
                    return true;
                }
                if (!consume(',')) {
                    return false;
                }
            }
        }
        if (input_[pos_] == '[') {
            ++pos_;
            skipWs();
            if (consume(']')) {
                return true;
            }
            while (true) {
                if (!skipValue()) {
                    return false;
                }
                skipWs();
                if (consume(']')) {
                    return true;
                }
                if (!consume(',')) {
                    return false;
                }
            }
        }

        while (pos_ < input_.size()) {
            char c = input_[pos_];
            if (c == ',' || c == '}' || c == ']' || c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                return true;
            }
            ++pos_;
        }
        return true;
    }

    std::string_view input_;
    std::size_t pos_ = 0;
};

bool validUtf8(const std::string &input) {
    std::size_t i = 0;
    while (i < input.size()) {
        uint8_t c = static_cast<uint8_t>(input[i]);
        if (c <= 0x7F) {
            ++i;
        } else if ((c >> 5) == 0x6) {
            if (i + 1 >= input.size()) {
                return false;
            }
            uint8_t c1 = static_cast<uint8_t>(input[i + 1]);
            if ((c1 >> 6) != 0x2 || c < 0xC2) {
                return false;
            }
            i += 2;
        } else if ((c >> 4) == 0xE) {
            if (i + 2 >= input.size()) {
                return false;
            }
            uint8_t c1 = static_cast<uint8_t>(input[i + 1]);
            uint8_t c2 = static_cast<uint8_t>(input[i + 2]);
            if ((c1 >> 6) != 0x2 || (c2 >> 6) != 0x2) {
                return false;
            }
            if ((c == 0xE0 && c1 < 0xA0) || (c == 0xED && c1 >= 0xA0)) {
                return false;
            }
            i += 3;
        } else if ((c >> 3) == 0x1E) {
            if (i + 3 >= input.size()) {
                return false;
            }
            uint8_t c1 = static_cast<uint8_t>(input[i + 1]);
            uint8_t c2 = static_cast<uint8_t>(input[i + 2]);
            uint8_t c3 = static_cast<uint8_t>(input[i + 3]);
            if ((c1 >> 6) != 0x2 || (c2 >> 6) != 0x2 || (c3 >> 6) != 0x2) {
                return false;
            }
            if ((c == 0xF0 && c1 < 0x90) || (c == 0xF4 && c1 >= 0x90) || c > 0xF4) {
                return false;
            }
            i += 4;
        } else {
            return false;
        }
    }
    return true;
}

std::optional<std::string> keyToFcitxName(const std::string &key) {
    if (key == "Space") {
        return "space";
    }
    if (key == "A" || key == "C" || key == "V" || key == "X" || key == "Z") {
        std::string lower;
        lower.push_back(static_cast<char>(key[0] - 'A' + 'a'));
        return lower;
    }
    if (key == "Return" || key == "Escape" || key == "Tab" || key == "BackSpace" ||
        key == "Delete" || key == "Left" || key == "Right" || key == "Up" ||
        key == "Down" || key == "Home" || key == "End" || key == "Page_Up" ||
        key == "Page_Down") {
        return key;
    }
    return std::nullopt;
}

std::optional<std::string> modifierToFcitxName(const std::string &modifier, const std::string &controlName) {
    if (modifier == "Control") {
        return controlName;
    }
    if (modifier == "Shift" || modifier == "Alt") {
        return modifier;
    }
    return std::nullopt;
}

std::optional<fcitx::Key> makeForwardKey(const std::string &keyName,
                                         const std::vector<std::string> &modifiers,
                                         const std::string &controlName) {
    auto fcitxKeyName = keyToFcitxName(keyName);
    if (!fcitxKeyName) {
        return std::nullopt;
    }

    std::string spec;
    for (const auto &modifier : modifiers) {
        auto fcitxModifier = modifierToFcitxName(modifier, controlName);
        if (!fcitxModifier) {
            return std::nullopt;
        }
        spec += *fcitxModifier;
        spec += "+";
    }
    spec += *fcitxKeyName;

    fcitx::Key key(spec);
    if (!key.isValid()) {
        return std::nullopt;
    }
    return key;
}

std::optional<fcitx::Key> makeForwardKey(const std::string &keyName,
                                         const std::vector<std::string> &modifiers) {
    auto key = makeForwardKey(keyName, modifiers, "Control");
    if (key) {
        return key;
    }
    return makeForwardKey(keyName, modifiers, "Ctrl");
}

std::string jsonEscape(const std::string &input) {
    std::string escaped;
    for (char c : input) {
        switch (c) {
        case '"':
            escaped += "\\\"";
            break;
        case '\\':
            escaped += "\\\\";
            break;
        case '\b':
            escaped += "\\b";
            break;
        case '\f':
            escaped += "\\f";
            break;
        case '\n':
            escaped += "\\n";
            break;
        case '\r':
            escaped += "\\r";
            break;
        case '\t':
            escaped += "\\t";
            break;
        default:
            if (static_cast<unsigned char>(c) < 0x20) {
                char buffer[7];
                std::snprintf(buffer, sizeof(buffer), "\\u%04x", static_cast<unsigned char>(c));
                escaped += buffer;
            } else {
                escaped.push_back(c);
            }
        }
    }
    return escaped;
}

std::string makeResponse(const std::string &id, const CommitResult &result) {
    std::string response = "{\"v\":1,\"id\":\"" + jsonEscape(id) + "\",\"status\":\"" +
                           jsonEscape(result.status) + "\"";
    if (!result.targetProgram.empty()) {
        response += ",\"target_program\":\"" + jsonEscape(result.targetProgram) + "\"";
    }
    response += "}";
    return response;
}

std::string socketPath() {
    const char *runtime = std::getenv("XDG_RUNTIME_DIR");
    std::string base = runtime && runtime[0] ? runtime : "/tmp";
    return base + "/textbridge/fcitx.sock";
}

std::string socketDir(const std::string &path) {
    auto pos = path.rfind('/');
    if (pos == std::string::npos) {
        return ".";
    }
    return path.substr(0, pos);
}

} // namespace

class TextBridge final : public fcitx::AddonInstance {
public:
    explicit TextBridge(fcitx::Instance *instance) : instance_(instance), socketPath_(socketPath()) {
        setupSocket();
    }

    ~TextBridge() override {
        socketEvent_.reset();
        if (fd_ >= 0) {
            close(fd_);
        }
        if (!socketPath_.empty()) {
            unlink(socketPath_.c_str());
        }
    }

private:
    void setupSocket() {
        std::string dir = socketDir(socketPath_);
        mkdir(dir.c_str(), 0700);
        chmod(dir.c_str(), 0700);
        unlink(socketPath_.c_str());

        fd_ = socket(AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC, 0);
        if (fd_ < 0) {
            FCITX_ERROR() << "textbridge: failed to create socket: " << strerror(errno);
            return;
        }

        int passcred = 1;
        if (setsockopt(fd_, SOL_SOCKET, SO_PASSCRED, &passcred, sizeof(passcred)) != 0) {
            FCITX_ERROR() << "textbridge: failed to enable SO_PASSCRED: " << strerror(errno);
            close(fd_);
            fd_ = -1;
            return;
        }

        sockaddr_un addr {};
        addr.sun_family = AF_UNIX;
        if (socketPath_.size() >= sizeof(addr.sun_path)) {
            FCITX_ERROR() << "textbridge: socket path too long: " << socketPath_;
            close(fd_);
            fd_ = -1;
            return;
        }
        std::strncpy(addr.sun_path, socketPath_.c_str(), sizeof(addr.sun_path) - 1);

        if (bind(fd_, reinterpret_cast<sockaddr *>(&addr), sizeof(addr)) != 0) {
            FCITX_ERROR() << "textbridge: failed to bind socket: " << strerror(errno);
            close(fd_);
            fd_ = -1;
            return;
        }
        chmod(socketPath_.c_str(), 0600);

        socketEvent_ = instance_->eventLoop().addIOEvent(
            fd_, fcitx::IOEventFlag::In,
            [this](fcitx::EventSource *, int, fcitx::IOEventFlags) {
                handleSocketReadable();
                return true;
            });

        FCITX_INFO() << "textbridge: listening on " << socketPath_;
    }

    void handleSocketReadable() {
        while (true) {
            char buffer[kMaxDatagramBytes + 1];
            char control[CMSG_SPACE(sizeof(ucred))];
            sockaddr_un peer {};
            iovec iov {};
            iov.iov_base = buffer;
            iov.iov_len = kMaxDatagramBytes;

            msghdr msg {};
            msg.msg_name = &peer;
            msg.msg_namelen = sizeof(peer);
            msg.msg_iov = &iov;
            msg.msg_iovlen = 1;
            msg.msg_control = control;
            msg.msg_controllen = sizeof(control);

            ssize_t size = recvmsg(fd_, &msg, MSG_DONTWAIT);
            if (size < 0) {
                if (errno == EAGAIN || errno == EWOULDBLOCK) {
                    return;
                }
                FCITX_WARN() << "textbridge: recvmsg failed: " << strerror(errno);
                return;
            }

            uid_t peerUid = static_cast<uid_t>(-1);
            for (cmsghdr *cmsg = CMSG_FIRSTHDR(&msg); cmsg != nullptr; cmsg = CMSG_NXTHDR(&msg, cmsg)) {
                if (cmsg->cmsg_level == SOL_SOCKET && cmsg->cmsg_type == SCM_CREDENTIALS) {
                    auto *cred = reinterpret_cast<ucred *>(CMSG_DATA(cmsg));
                    peerUid = cred->uid;
                }
            }

            std::string raw(buffer, buffer + size);
            auto parsed = JsonReader(raw).parseRequest();
            std::string id = parsed ? parsed->id : "";

            CommitResult result;
            if (peerUid != geteuid()) {
                result = {"unauthorized", ""};
            } else if (!parsed) {
                result = {"invalid_request", ""};
            } else if (parsed->action == "key") {
                result = forwardKey(parsed->key, parsed->modifiers);
            } else {
                result = commitText(parsed->text);
            }

            sendResponse(peer, msg.msg_namelen, makeResponse(id, result));
        }
    }

    CommitResult commitText(const std::string &text) {
        if (text.empty() || text.size() > kMaxTextBytes || !validUtf8(text)) {
            return {"invalid_text", ""};
        }

        auto *ic = instance_->lastFocusedInputContext();
        if (!ic || !ic->hasFocus()) {
            return {"no_focused_input", ""};
        }

        std::string program = ic->program();
        if (instance_->isComposing(ic)) {
            return {"busy_composing", program};
        }

        auto flags = ic->capabilityFlags();
        if (flags.test(fcitx::CapabilityFlag::PasswordOrSensitive)) {
            return {"sensitive_field", program};
        }

        ic->commitString(text);
        return {"ok", program};
    }

    CommitResult forwardKey(const std::string &keyName, const std::vector<std::string> &modifiers) {
        auto key = makeForwardKey(keyName, modifiers);
        if (!key) {
            return {"invalid_key", ""};
        }

        auto *ic = instance_->lastFocusedInputContext();
        if (!ic || !ic->hasFocus()) {
            return {"no_focused_input", ""};
        }

        std::string program = ic->program();
        if (instance_->isComposing(ic)) {
            return {"busy_composing", program};
        }

        auto flags = ic->capabilityFlags();
        if (flags.test(fcitx::CapabilityFlag::PasswordOrSensitive)) {
            return {"sensitive_field", program};
        }

        ic->forwardKey(*key, false);
        ic->forwardKey(*key, true);
        return {"ok", program};
    }

    void sendResponse(const sockaddr_un &peer, socklen_t peerLen, const std::string &response) {
        if (peerLen == 0 || peer.sun_path[0] == '\0') {
            return;
        }
        if (sendto(fd_, response.data(), response.size(), 0,
                   reinterpret_cast<const sockaddr *>(&peer), peerLen) < 0) {
            FCITX_WARN() << "textbridge: sendto failed: " << strerror(errno);
        }
    }

    fcitx::Instance *instance_;
    int fd_ = -1;
    std::string socketPath_;
    std::unique_ptr<fcitx::EventSource> socketEvent_;
};

class TextBridgeFactory final : public fcitx::AddonFactory {
public:
    fcitx::AddonInstance *create(fcitx::AddonManager *manager) override {
        return new TextBridge(manager->instance());
    }
};

FCITX_ADDON_FACTORY_V2(textbridge, TextBridgeFactory)
