// Stand-ins for the mDNS discovery code that this reduced build leaves out (openscreen and Bonjour).
// adb pair and adb connect with an explicit host:port do not use discovery, so every query reports
// "nothing found" and discovery is reported as disabled.
#include <optional>
#include <string>
#include <string_view>

#include "adb_mdns.h"
#include "client/mdns_utils.h"
#include "client/usb.h"
#include "transport.h"
#include "client/fastdeploy.h"
#include "client/incremental.h"

namespace mdns {

std::optional<MdnsInstance> mdns_parse_instance_name(std::string_view) {
    return std::nullopt;
}

bool is_enabled() {
    return false;
}

}  // namespace mdns

bool adb_secure_connect_by_service_name(const std::string&) {
    return false;
}

std::optional<int> adb_DNSServiceIndexByName(std::string_view) {
    return std::nullopt;
}

bool adb_DNSServiceShouldAutoConnect(std::string_view, std::string_view) {
    return false;
}

void mdns_cleanup() {}

std::string mdns_check() {
    return "mDNS discovery is not built into this adb";
}

std::string mdns_list_discovered_services() {
    return "";
}

std::optional<MdnsInfo> mdns_get_connect_service_info(const std::string&) {
    return std::nullopt;
}

std::optional<MdnsInfo> mdns_get_pairing_service_info(const std::string&) {
    return std::nullopt;
}

static std::string NoMdnsCheck() { return mdns_check(); }
static std::string NoMdnsList() { return mdns_list_discovered_services(); }
static std::optional<MdnsInfo> NoMdnsConnect(const std::string& n) { return mdns_get_connect_service_info(n); }
static std::optional<MdnsInfo> NoMdnsPairing(const std::string& n) { return mdns_get_pairing_service_info(n); }
static void NoMdnsCleanupFn() { mdns_cleanup(); }
static bool NoMdnsSecureConnect(const std::string& n) { return adb_secure_connect_by_service_name(n); }

AdbMdnsResponderFuncs StartMdnsResponderDiscovery() {
    return AdbMdnsResponderFuncs{
            .mdns_check = NoMdnsCheck,
            .mdns_list_discovered_services = NoMdnsList,
            .mdns_get_connect_service_info = NoMdnsConnect,
            .mdns_get_pairing_service_info = NoMdnsPairing,
            .mdns_cleanup = NoMdnsCleanupFn,
            .adb_secure_connect_by_service_name = NoMdnsSecureConnect,
    };
}

// USB transports are not built: adb connects over TCP only.
void usb_init() {}
void usb_cleanup() {}
bool is_libusb_enabled() { return false; }

namespace libusb {
void usb_init() {}
}  // namespace libusb

// Only the USB and incremental-install paths call these; nothing in this build reaches them.
void init_usb_transport(atransport*, usb_handle*) {}
void init_mdns_transport_discovery() {}

namespace incremental {
bool serve(int, int, int, const char**) { return false; }
}  // namespace incremental

bool using_bonjour(void) { return false; }

// adb install (incremental and fastdeploy) is not built: these report "not available".
namespace incremental {
bool can_install(const Files&) { return false; }
std::optional<Process> install(const Files&, const Args&, bool) { return std::nullopt; }
}  // namespace incremental

void fastdeploy_set_agent_update_strategy(FastDeploy_AgentUpdateStrategy) {}
int get_device_api_level() { return 0; }
std::optional<com::android::fastdeploy::APKMetaData> extract_metadata(const char*) { return std::nullopt; }
unique_fd install_patch(int, const char**) { return unique_fd(); }
unique_fd apply_patch_on_device(const char*) { return unique_fd(); }
int stream_patch(const char*, com::android::fastdeploy::APKMetaData, unique_fd) { return -1; }
