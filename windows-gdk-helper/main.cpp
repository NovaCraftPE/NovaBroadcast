#include <windows.h>
#include <winhttp.h>
#include <XGameRuntime.h>
#include <XUser.h>
#include <XTaskQueue.h>

#include <cstdint>
#include <future>
#include <iostream>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace {
constexpr wchar_t kScid[] = L"00000000-0000-0000-0000-0000690d1c50";
constexpr wchar_t kTemplate[] = L"NovaBroadcastSession";
constexpr wchar_t kMpsdBase[] = L"https://sessiondirectory.xboxlive.com";
constexpr wchar_t kContractVersion[] = L"107";

std::string hrText(HRESULT hr) {
    char buffer[32]{};
    sprintf_s(buffer, "0x%08X", static_cast<unsigned int>(hr));
    return buffer;
}

void check(HRESULT hr, const char* what) {
    if (FAILED(hr)) {
        throw std::runtime_error(std::string(what) + " failed: " + hrText(hr));
    }
}

struct UserAddResult {
    HRESULT hr{};
    XUserHandle user{};
};

struct TokenSignatureResult {
    HRESULT hr{};
    std::wstring token;
    std::wstring signature;
};

struct WinHttpHandle {
    HINTERNET value{};
    WinHttpHandle() = default;
    explicit WinHttpHandle(HINTERNET v) : value(v) {}
    ~WinHttpHandle() { if (value) WinHttpCloseHandle(value); }
    WinHttpHandle(const WinHttpHandle&) = delete;
    WinHttpHandle& operator=(const WinHttpHandle&) = delete;
    operator HINTERNET() const { return value; }
};

UserAddResult signIn(XTaskQueueHandle queue) {
    std::promise<UserAddResult> promise;
    auto future = promise.get_future();

    XAsyncBlock async{};
    async.queue = queue;
    async.context = &promise;
    async.callback = [](XAsyncBlock* block) {
        auto* p = static_cast<std::promise<UserAddResult>*>(block->context);
        XUserHandle user{};
        HRESULT hr = XUserAddResult(block, &user);
        p->set_value({hr, user});
    };

    check(XUserAddAsync(XUserAddOptions::AddDefaultUserAllowingUI, &async), "XUserAddAsync");
    return future.get();
}

TokenSignatureResult signRequest(
    XTaskQueueHandle queue,
    XUserHandle user,
    const std::wstring& method,
    const std::wstring& url)
{
    std::promise<TokenSignatureResult> promise;
    auto future = promise.get_future();

    XUserGetTokenAndSignatureUtf16HttpHeader signedHeaders[] = {
        {L"Accept", L"application/json"},
        {L"x-xbl-contract-version", kContractVersion},
    };

    XAsyncBlock async{};
    async.queue = queue;
    async.context = &promise;
    async.callback = [](XAsyncBlock* block) {
        auto* p = static_cast<std::promise<TokenSignatureResult>*>(block->context);
        size_t size = 0;
        HRESULT hr = XUserGetTokenAndSignatureUtf16ResultSize(block, &size);
        if (FAILED(hr)) {
            p->set_value({hr, {}, {}});
            return;
        }

        std::vector<std::uint8_t> buffer(size);
        XUserGetTokenAndSignatureUtf16Data* data = nullptr;
        hr = XUserGetTokenAndSignatureUtf16Result(
            block, buffer.size(), buffer.data(), &data, nullptr);
        if (FAILED(hr) || data == nullptr) {
            p->set_value({hr, {}, {}});
            return;
        }

        std::wstring token = data->token ? data->token : L"";
        std::wstring signature = data->signature ? data->signature : L"";
        p->set_value({hr, std::move(token), std::move(signature)});
    };

    check(XUserGetTokenAndSignatureUtf16Async(
        user,
        XUserGetTokenAndSignatureOptions::None,
        method.c_str(),
        url.c_str(),
        _countof(signedHeaders),
        signedHeaders,
        0,
        nullptr,
        &async),
        "XUserGetTokenAndSignatureUtf16Async");

    return future.get();
}

struct HttpResult {
    DWORD status{};
    std::string body;
};

HttpResult signedGet(const std::wstring& url, const std::wstring& token, const std::wstring& signature) {
    URL_COMPONENTS parts{};
    parts.dwStructSize = sizeof(parts);
    wchar_t host[256]{};
    wchar_t path[2048]{};
    parts.lpszHostName = host;
    parts.dwHostNameLength = _countof(host);
    parts.lpszUrlPath = path;
    parts.dwUrlPathLength = _countof(path);

    if (!WinHttpCrackUrl(url.c_str(), 0, 0, &parts)) {
        throw std::runtime_error("WinHttpCrackUrl failed");
    }

    std::wstring hostName(parts.lpszHostName, parts.dwHostNameLength);
    std::wstring urlPath(parts.lpszUrlPath, parts.dwUrlPathLength);
    if (parts.dwExtraInfoLength && parts.lpszExtraInfo) {
        urlPath.append(parts.lpszExtraInfo, parts.dwExtraInfoLength);
    }

    WinHttpHandle session(WinHttpOpen(
        L"NovaBroadcastGdkHelper/0.1",
        WINHTTP_ACCESS_TYPE_AUTOMATIC_PROXY,
        WINHTTP_NO_PROXY_NAME,
        WINHTTP_NO_PROXY_BYPASS,
        0));
    if (!session.value) throw std::runtime_error("WinHttpOpen failed");

    WinHttpHandle connect(WinHttpConnect(session, hostName.c_str(), parts.nPort, 0));
    if (!connect.value) throw std::runtime_error("WinHttpConnect failed");

    WinHttpHandle request(WinHttpOpenRequest(
        connect,
        L"GET",
        urlPath.c_str(),
        nullptr,
        WINHTTP_NO_REFERER,
        WINHTTP_DEFAULT_ACCEPT_TYPES,
        WINHTTP_FLAG_SECURE));
    if (!request.value) throw std::runtime_error("WinHttpOpenRequest failed");

    std::wstring headers =
        L"Accept: application/json\r\n"
        L"x-xbl-contract-version: 107\r\n"
        L"Authorization: " + token + L"\r\n" +
        L"Signature: " + signature + L"\r\n";

    if (!WinHttpAddRequestHeaders(request, headers.c_str(), -1L,
                                  WINHTTP_ADDREQ_FLAG_ADD | WINHTTP_ADDREQ_FLAG_REPLACE)) {
        throw std::runtime_error("WinHttpAddRequestHeaders failed");
    }

    if (!WinHttpSendRequest(request, WINHTTP_NO_ADDITIONAL_HEADERS, 0,
                            WINHTTP_NO_REQUEST_DATA, 0, 0, 0)) {
        throw std::runtime_error("WinHttpSendRequest failed");
    }
    if (!WinHttpReceiveResponse(request, nullptr)) {
        throw std::runtime_error("WinHttpReceiveResponse failed");
    }

    DWORD status = 0;
    DWORD statusSize = sizeof(status);
    if (!WinHttpQueryHeaders(request,
                             WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                             WINHTTP_HEADER_NAME_BY_INDEX,
                             &status,
                             &statusSize,
                             WINHTTP_NO_HEADER_INDEX)) {
        throw std::runtime_error("WinHttpQueryHeaders(status) failed");
    }

    std::string body;
    for (;;) {
        DWORD available = 0;
        if (!WinHttpQueryDataAvailable(request, &available)) {
            throw std::runtime_error("WinHttpQueryDataAvailable failed");
        }
        if (available == 0) break;
        size_t oldSize = body.size();
        body.resize(oldSize + available);
        DWORD read = 0;
        if (!WinHttpReadData(request, body.data() + oldSize, available, &read)) {
            throw std::runtime_error("WinHttpReadData failed");
        }
        body.resize(oldSize + read);
    }

    return {status, std::move(body)};
}
} // namespace

int wmain() {
    XTaskQueueHandle queue{};
    XUserHandle user{};

    try {
        std::wcout << L"NovaBroadcast Windows GDK Helper 0.1\n";
        std::wcout << L"Read-only title-bound MPSD preflight. No session writes are performed.\n";
        std::wcout << L"SCID: " << kScid << L"\nTemplate: " << kTemplate << L"\n\n";

        check(XGameRuntimeInitialize(), "XGameRuntimeInitialize");
        check(XTaskQueueCreate(XTaskQueueDispatchMode::ThreadPool,
                               XTaskQueueDispatchMode::ThreadPool,
                               &queue),
              "XTaskQueueCreate");

        std::cout << "[GDK] Signing in Xbox user (UI may appear)...\n";
        UserAddResult signedIn = signIn(queue);
        check(signedIn.hr, "XUserAddResult");
        user = signedIn.user;
        std::cout << "[GDK] Xbox user acquired.\n";

        std::wstring url = std::wstring(kMpsdBase) +
            L"/serviceconfigs/" + kScid +
            L"/sessiontemplates/" + kTemplate;

        std::cout << "[GDK] Requesting title-bound X-token + Signature for exact MPSD GET...\n";
        TokenSignatureResult auth = signRequest(queue, user, L"GET", url);
        check(auth.hr, "XUserGetTokenAndSignatureUtf16Result");
        if (auth.token.empty()) throw std::runtime_error("GDK returned an empty X-token");
        if (auth.signature.empty()) throw std::runtime_error("GDK returned an empty Signature");
        std::cout << "[GDK] Token/signature acquired (values intentionally not logged or persisted).\n";

        HttpResult response = signedGet(url, auth.token, auth.signature);
        auth.token.clear();
        auth.signature.clear();

        std::cout << "[MPSD] HTTP " << response.status << "\n";
        if (response.status >= 200 && response.status < 300) {
            std::cout << "[MPSD] PASS: NovaBroadcast title identity can access its configured MPSD template.\n";
            std::cout << "[MPSD] No write was attempted.\n";
            return 0;
        }

        std::cout << "[MPSD] BLOCKED. Response body (no auth tokens):\n";
        if (response.body.size() > 4096) response.body.resize(4096);
        std::cout << response.body << "\n";
        return 2;
    } catch (const std::exception& e) {
        std::cerr << "[NovaBroadcastGdkHelper] " << e.what() << "\n";
        return 1;
    }

    if (user) XUserCloseHandle(user);
    if (queue) XTaskQueueCloseHandle(queue);
    XGameRuntimeUninitialize();
    return 0;
}
