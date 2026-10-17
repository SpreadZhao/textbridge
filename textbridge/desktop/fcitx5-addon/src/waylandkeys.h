#pragma once

#include <fcitx-utils/key.h>

#include <string>
#include <vector>

namespace textbridge {

// Wayland's Fcitx forwardKey path does not apply synthetic modifier states.
// Use the compositor's virtual keyboard protocol for the complete key chord.
std::vector<std::string> waylandKeyArguments(const fcitx::Key &key);
bool forwardWaylandKey(const fcitx::Key &key, const std::string &executable);

} // namespace textbridge
