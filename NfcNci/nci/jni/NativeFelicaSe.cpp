//
// Copyright (C) 2026 The 2by2 Project
// SPDX-License-Identifier: Apache-2.0
//

#include <android-base/logging.h>
#include <android-base/stringprintf.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedPrimitiveArray.h>

#include <vector>

#include "NfcJniUtil.h"
#include "SyncEvent.h"
#include "nfa_api.h"
#include "nfa_ee_api.h"
#include "nfc_config.h"
#include "nfc_target.h"

using android::base::StringPrintf;

namespace android {
extern bool nfcManager_isNfcActive();

namespace {
constexpr const char* kNativeFelicaSeClassName =
    "jp/project2by2/felica/NativeFelicaSe";
constexpr int kFelicaSeTimeoutMs = 1000;
constexpr int kErrorNone = 0;
constexpr int kErrorFailed = -99;
constexpr int kErrorInvalidParam = -10;
constexpr int kErrorBusy = -11;
constexpr int kErrorNotAvailable = -18;
constexpr uint8_t kFelicaSeInterface = 0x02;  // NCI_NFCEE_INTERFACE_T3T

SyncEvent sFelicaSeConnEvent;
SyncEvent sFelicaSeDataEvent;
SyncEvent sFelicaSeDisconnectEvent;
SyncEvent sFelicaSeRegisterEvent;
SyncEvent sFelicaSeModeSetEvent;
std::vector<uint8_t> sFelicaSeRxData;
tNFA_STATUS sFelicaSeStatus = NFA_STATUS_FAILED;
tNFA_STATUS sFelicaSeRegisterStatus = NFA_STATUS_FAILED;
tNFA_STATUS sFelicaSeModeSetStatus = NFA_STATUS_FAILED;
tNFA_HANDLE sFelicaSeHandle = NFA_HANDLE_INVALID;
bool sFelicaSeEeRegistered = false;
bool sFelicaSeConnected = false;

tNFA_HANDLE normalizeEeHandle(tNFA_HANDLE handle) {
  if (handle != 0 && (handle & NFA_HANDLE_GROUP_MASK) == 0) {
    handle |= NFA_HANDLE_GROUP_EE;
  }
  return handle;
}

bool hasInterface(const tNFA_EE_INFO& info, uint8_t eeInterface) {
  for (uint8_t i = 0; i < info.num_interface; i++) {
    if (info.ee_interface[i] == eeInterface) {
      return true;
    }
  }
  return false;
}

tNFA_HANDLE getConfiguredEseHandle() {
  tNFA_HANDLE handle = NfcConfig::getUnsigned("NFCEE_ID_ESE", 0);
  if (handle == 0 && NfcConfig::hasKey(NAME_OFFHOST_ROUTE_ESE)) {
    std::vector<uint8_t> routes = NfcConfig::getBytes(NAME_OFFHOST_ROUTE_ESE);
    if (!routes.empty()) {
      handle = routes[0];
    }
  }
  if (handle == 0) {
    handle = 0x402;
  }
  return normalizeEeHandle(handle);
}

tNFA_HANDLE findFelicaSeHandle() {
  tNFA_HANDLE configuredHandle = getConfiguredEseHandle();
  tNFA_EE_INFO eeInfo[NFA_EE_MAX_EE_SUPPORTED] = {};
  uint8_t actualNumEe = NFA_EE_MAX_EE_SUPPORTED;
  tNFA_STATUS status = NFA_EeGetInfo(&actualNumEe, eeInfo);
  if (status != NFA_STATUS_OK) {
    LOG(ERROR) << StringPrintf("%s: NFA_EeGetInfo failed status=0x%02X",
                               __func__, status);
    return configuredHandle;
  }

  LOG(INFO) << StringPrintf("%s: discovered %u NFCEE(s), configured=0x%04X",
                            __func__, actualNumEe, configuredHandle);
  tNFA_HANDLE firstT3tHandle = NFA_HANDLE_INVALID;
  for (uint8_t i = 0; i < actualNumEe; i++) {
    tNFA_HANDLE handle = normalizeEeHandle(eeInfo[i].ee_handle);
    LOG(INFO) << StringPrintf(
        "%s: NFCEE[%u] handle=0x%04X status=0x%02X interfaces=%u [%02X,%02X,%02X]",
        __func__, i, handle, eeInfo[i].ee_status, eeInfo[i].num_interface,
        eeInfo[i].num_interface > 0 ? eeInfo[i].ee_interface[0] : 0xFF,
        eeInfo[i].num_interface > 1 ? eeInfo[i].ee_interface[1] : 0xFF,
        eeInfo[i].num_interface > 2 ? eeInfo[i].ee_interface[2] : 0xFF);

    if (!hasInterface(eeInfo[i], kFelicaSeInterface)) {
      continue;
    }
    if (firstT3tHandle == NFA_HANDLE_INVALID) {
      firstT3tHandle = handle;
    }
    if (handle == configuredHandle) {
      return handle;
    }
  }

  if (firstT3tHandle != NFA_HANDLE_INVALID) {
    LOG(INFO) << StringPrintf("%s: using discovered T3T NFCEE handle=0x%04X",
                              __func__, firstT3tHandle);
    return firstT3tHandle;
  }

  LOG(WARNING) << StringPrintf(
      "%s: no discovered T3T NFCEE, falling back to configured handle=0x%04X",
      __func__, configuredHandle);
  return configuredHandle;
}

void nfaEeCallback(tNFA_EE_EVT event, tNFA_EE_CBACK_DATA* data) {
  switch (event) {
    case NFA_EE_REGISTER_EVT:
      sFelicaSeRegisterStatus =
          data != nullptr ? data->ee_register : NFA_STATUS_FAILED;
      {
        SyncEventGuard guard(sFelicaSeRegisterEvent);
        sFelicaSeRegisterEvent.notifyOne();
      }
      break;
    case NFA_EE_MODE_SET_EVT:
      sFelicaSeModeSetStatus =
          data != nullptr ? data->mode_set.status : NFA_STATUS_FAILED;
      {
        SyncEventGuard guard(sFelicaSeModeSetEvent);
        sFelicaSeModeSetEvent.notifyOne();
      }
      break;
    case NFA_EE_CONNECT_EVT:
      sFelicaSeStatus = data != nullptr ? data->connect.status : NFA_STATUS_FAILED;
      {
        SyncEventGuard guard(sFelicaSeConnEvent);
        sFelicaSeConnEvent.notifyOne();
      }
      break;
    case NFA_EE_DATA_EVT:
      sFelicaSeStatus = NFA_STATUS_OK;
      sFelicaSeRxData.clear();
      if (data != nullptr && data->data.p_buf != nullptr && data->data.len > 0) {
        sFelicaSeRxData.insert(sFelicaSeRxData.end(), data->data.p_buf,
                               data->data.p_buf + data->data.len);
      }
      {
        SyncEventGuard guard(sFelicaSeDataEvent);
        sFelicaSeDataEvent.notifyOne();
      }
      break;
    case NFA_EE_DISCONNECT_EVT:
      sFelicaSeStatus = data != nullptr ? data->status : NFA_STATUS_FAILED;
      {
        SyncEventGuard guard(sFelicaSeDisconnectEvent);
        sFelicaSeDisconnectEvent.notifyOne();
      }
      break;
    case NFA_EE_NO_MEM_ERR_EVT:
    case NFA_EE_NO_CB_ERR_EVT:
      sFelicaSeStatus = data != nullptr ? data->status : NFA_STATUS_FAILED;
      {
        SyncEventGuard guard(sFelicaSeDataEvent);
        sFelicaSeDataEvent.notifyOne();
      }
      break;
    default:
      break;
  }
}

bool ensureEeRegistered() {
  if (sFelicaSeEeRegistered) {
    return true;
  }

  tNFA_STATUS status;
  {
    SyncEventGuard guard(sFelicaSeRegisterEvent);
    sFelicaSeRegisterStatus = NFA_STATUS_FAILED;
    status = NFA_EeRegister(nfaEeCallback);
    if (status == NFA_STATUS_OK) {
      if (!sFelicaSeRegisterEvent.wait(kFelicaSeTimeoutMs)) {
        status = NFA_STATUS_FAILED;
      } else {
        status = sFelicaSeRegisterStatus;
      }
    }
  }

  if (status != NFA_STATUS_OK) {
    LOG(ERROR) << StringPrintf("%s: NFA_EeRegister failed status=0x%02X",
                               __func__, status);
    return false;
  }
  sFelicaSeEeRegistered = true;
  return true;
}

bool setEeMode(tNFA_HANDLE handle, tNFA_EE_MD mode) {
  if (!ensureEeRegistered()) {
    return false;
  }

  tNFA_STATUS status;
  {
    SyncEventGuard guard(sFelicaSeModeSetEvent);
    sFelicaSeModeSetStatus = NFA_STATUS_FAILED;
    status = NFA_EeModeSet(handle, mode);
    if (status == NFA_STATUS_OK) {
      if (!sFelicaSeModeSetEvent.wait(kFelicaSeTimeoutMs)) {
        status = NFA_STATUS_FAILED;
      } else {
        status = sFelicaSeModeSetStatus;
      }
    }
  }

  LOG(INFO) << StringPrintf("%s: handle=0x%04X mode=0x%02X status=0x%02X",
                            __func__, handle, mode, status);
  return status == NFA_STATUS_OK;
}

tNFA_STATUS connectFelicaSe(tNFA_HANDLE handle) {
  tNFA_STATUS status;
  {
    SyncEventGuard guard(sFelicaSeConnEvent);
    status = NFA_EeConnect(handle, kFelicaSeInterface, nfaEeCallback);
    if (status == NFA_STATUS_OK) {
      if (!sFelicaSeConnEvent.wait(kFelicaSeTimeoutMs)) {
        status = NFA_STATUS_FAILED;
      } else {
        status = sFelicaSeStatus;
      }
    }
  }
  return status;
}

jint nativeFelicaSe_doOpen(JNIEnv*, jobject) {
  if (!nfcManager_isNfcActive()) {
    LOG(ERROR) << StringPrintf("%s: NFC is not active", __func__);
    return kErrorNotAvailable;
  }
  if (sFelicaSeConnected) {
    return sFelicaSeHandle;
  }

  sFelicaSeHandle = findFelicaSeHandle();

  tNFA_STATUS status = connectFelicaSe(sFelicaSeHandle);
  if (status == NFA_STATUS_REJECTED) {
    LOG(WARNING) << StringPrintf(
        "%s: connect rejected, resetting NFCEE mode handle=0x%04X",
        __func__, sFelicaSeHandle);
    if (setEeMode(sFelicaSeHandle, NFA_EE_MD_DEACTIVATE) &&
        setEeMode(sFelicaSeHandle, NFA_EE_MD_ACTIVATE)) {
      status = connectFelicaSe(sFelicaSeHandle);
    }
  }

  if (status != NFA_STATUS_OK) {
    LOG(ERROR) << StringPrintf(
        "%s: NFA_EeConnect failed handle=0x%04X interface=0x%02X status=0x%02X",
        __func__, sFelicaSeHandle, kFelicaSeInterface, status);
    sFelicaSeHandle = NFA_HANDLE_INVALID;
    return kErrorFailed;
  }

  sFelicaSeConnected = true;
  LOG(INFO) << StringPrintf("%s: connected handle=0x%04X", __func__, sFelicaSeHandle);
  return sFelicaSeHandle;
}

jint nativeFelicaSe_doClose(JNIEnv*, jobject, jint handle) {
  if (!sFelicaSeConnected || handle != sFelicaSeHandle) {
    return kErrorInvalidParam;
  }

  tNFA_STATUS status;
  {
    SyncEventGuard guard(sFelicaSeDisconnectEvent);
    status = NFA_EeDisconnect(sFelicaSeHandle);
    if (status == NFA_STATUS_OK) {
      if (!sFelicaSeDisconnectEvent.wait(kFelicaSeTimeoutMs)) {
        status = NFA_STATUS_FAILED;
      } else {
        status = sFelicaSeStatus;
      }
    }
  }

  sFelicaSeConnected = false;
  sFelicaSeHandle = NFA_HANDLE_INVALID;
  sFelicaSeRxData.clear();

  return status == NFA_STATUS_OK ? kErrorNone : kErrorFailed;
}

jbyteArray nativeFelicaSe_doTransceive(JNIEnv* env, jobject, jint handle,
                                       jbyteArray command, jint timeoutMs,
                                       jintArray error) {
  if (error == nullptr) {
    return nullptr;
  }
  jint* errorData = env->GetIntArrayElements(error, nullptr);
  if (errorData == nullptr) {
    return nullptr;
  }
  errorData[0] = kErrorFailed;

  if (!sFelicaSeConnected || handle != sFelicaSeHandle || command == nullptr ||
      env->GetArrayLength(command) == 0) {
    errorData[0] = kErrorInvalidParam;
    env->ReleaseIntArrayElements(error, errorData, 0);
    return nullptr;
  }

  ScopedByteArrayRO commandBytes(env, command);
  if (commandBytes.get() == nullptr) {
    env->ReleaseIntArrayElements(error, errorData, 0);
    return nullptr;
  }

  tNFA_STATUS status;
  {
    SyncEventGuard guard(sFelicaSeDataEvent);
    sFelicaSeRxData.clear();
    status = NFA_EeSendData(sFelicaSeHandle, commandBytes.size(),
                            reinterpret_cast<uint8_t*>(const_cast<jbyte*>(commandBytes.get())));
    if (status == NFA_STATUS_OK) {
      int waitMs = timeoutMs > 0 ? timeoutMs : kFelicaSeTimeoutMs;
      if (!sFelicaSeDataEvent.wait(waitMs)) {
        status = NFA_STATUS_FAILED;
      } else {
        status = sFelicaSeStatus;
      }
    }
  }

  if (status != NFA_STATUS_OK) {
    LOG(ERROR) << StringPrintf("%s: NFA_EeSendData failed status=0x%02X", __func__, status);
    env->ReleaseIntArrayElements(error, errorData, 0);
    return nullptr;
  }

  jbyteArray result = env->NewByteArray(sFelicaSeRxData.size());
  if (result == nullptr) {
    env->ReleaseIntArrayElements(error, errorData, 0);
    return nullptr;
  }
  if (!sFelicaSeRxData.empty()) {
    env->SetByteArrayRegion(result, 0, sFelicaSeRxData.size(),
                            reinterpret_cast<const jbyte*>(sFelicaSeRxData.data()));
  }
  errorData[0] = kErrorNone;
  env->ReleaseIntArrayElements(error, errorData, 0);
  return result;
}

void nativeFelicaSe_doCancel(JNIEnv*, jobject, jint) {
  SyncEventGuard guard(sFelicaSeDataEvent);
  sFelicaSeStatus = NFA_STATUS_FAILED;
  sFelicaSeDataEvent.notifyOne();
}

static JNINativeMethod gMethods[] = {
    {"doOpen", "()I", (void*)nativeFelicaSe_doOpen},
    {"doClose", "(I)I", (void*)nativeFelicaSe_doClose},
    {"doTransceive", "(I[BI[I)[B", (void*)nativeFelicaSe_doTransceive},
    {"doCancel", "(I)V", (void*)nativeFelicaSe_doCancel},
};
}  // namespace

int register_com_android_nfc_NativeFelicaSe(JNIEnv* env) {
  return jniRegisterNativeMethods(env, kNativeFelicaSeClassName, gMethods,
                                  NELEM(gMethods));
}
}  // namespace android
