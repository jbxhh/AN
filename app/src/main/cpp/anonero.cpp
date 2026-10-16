/**
 * Copyright (c) 2017 m2049r
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <inttypes.h>
#include <mutex>
#include <string>
#include "anonero.h"
#include "wallet2_api.h"

//TODO explicit casting jlong, jint, jboolean to avoid warnings


// Releases JNI UTF buffers on every return path. Nullable Kotlin strings are
// converted to an empty string, matching the native wallet API's string inputs.
class ScopedUtfChars {
public:
    ScopedUtfChars(JNIEnv *env, jstring value) : env_(env), value_(value), chars_(nullptr) {
        if (value_ != nullptr) {
            chars_ = env_->GetStringUTFChars(value_, nullptr);
        }
    }

    ~ScopedUtfChars() {
        if (env_ != nullptr && value_ != nullptr && chars_ != nullptr) {
            env_->ReleaseStringUTFChars(value_, chars_);
        }
    }

    ScopedUtfChars(const ScopedUtfChars &) = delete;
    ScopedUtfChars &operator=(const ScopedUtfChars &) = delete;

    const char *get() const {
        return value_ == nullptr ? "" : chars_;
    }

private:
    JNIEnv *env_;
    jstring value_;
    const char *chars_;
};

#ifdef __cplusplus
extern "C"
{
#endif

#include <android/log.h>
#define LOG_TAG "WalletNDK"
#define LOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, LOG_TAG,__VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG  , LOG_TAG,__VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO   , LOG_TAG,__VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN   , LOG_TAG,__VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR  , LOG_TAG,__VA_ARGS__)

static JavaVM *cachedJVM;
static jclass class_ArrayList;
static jclass class_WalletListener;
static jclass class_TransactionInfo;
static jclass class_Transfer;
static jclass class_Ledger;
static jclass class_WalletStatus;
static jclass class_CoinsInfo;
static jclass class_Pair;

std::mutex _listenerMutex;

static jclass cacheGlobalClass(JNIEnv *env, const char *className) {
    jclass localClass = env->FindClass(className);
    if (localClass == nullptr || env->ExceptionCheck()) {
        LOGE("JNI_OnLoad: cannot find required class %s", className);
        if (localClass != nullptr) env->DeleteLocalRef(localClass);
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }

    jclass globalClass = static_cast<jclass>(env->NewGlobalRef(localClass));
    env->DeleteLocalRef(localClass);
    if (globalClass == nullptr || env->ExceptionCheck()) {
        LOGE("JNI_OnLoad: cannot cache required class %s", className);
        if (globalClass != nullptr) env->DeleteGlobalRef(globalClass);
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
    return globalClass;
}

static void clearCachedClasses(JNIEnv *env) {
    if (class_ArrayList != nullptr) env->DeleteGlobalRef(class_ArrayList);
    if (class_WalletListener != nullptr) env->DeleteGlobalRef(class_WalletListener);
    if (class_TransactionInfo != nullptr) env->DeleteGlobalRef(class_TransactionInfo);
    if (class_Transfer != nullptr) env->DeleteGlobalRef(class_Transfer);
    if (class_Ledger != nullptr) env->DeleteGlobalRef(class_Ledger);
    if (class_WalletStatus != nullptr) env->DeleteGlobalRef(class_WalletStatus);
    if (class_CoinsInfo != nullptr) env->DeleteGlobalRef(class_CoinsInfo);
    if (class_Pair != nullptr) env->DeleteGlobalRef(class_Pair);
    class_ArrayList = nullptr;
    class_WalletListener = nullptr;
    class_TransactionInfo = nullptr;
    class_Transfer = nullptr;
    class_Ledger = nullptr;
    class_WalletStatus = nullptr;
    class_CoinsInfo = nullptr;
    class_Pair = nullptr;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *jvm, void *reserved) {
    cachedJVM = jvm;
    LOGI("JNI_OnLoad");
    JNIEnv *jenv = nullptr;
    if (jvm->GetEnv(reinterpret_cast<void **>(&jenv), JNI_VERSION_1_6) != JNI_OK || jenv == nullptr) {
        LOGE("JNI_OnLoad: JNI 1.6 environment is unavailable");
        return JNI_ERR;
    }

#define CACHE_REQUIRED_CLASS(target, name) \
    do { \
        target = cacheGlobalClass(jenv, name); \
        if (target == nullptr) { \
            clearCachedClasses(jenv); \
            return JNI_ERR; \
        } \
    } while (0)

    CACHE_REQUIRED_CLASS(class_ArrayList, "java/util/ArrayList");
    CACHE_REQUIRED_CLASS(class_TransactionInfo, "io/anonero/model/TransactionInfo");
    CACHE_REQUIRED_CLASS(class_Transfer, "io/anonero/model/Transfer");
    CACHE_REQUIRED_CLASS(class_WalletListener, "io/anonero/model/WalletListener");
    CACHE_REQUIRED_CLASS(class_WalletStatus, "io/anonero/model/Wallet$Status");
    CACHE_REQUIRED_CLASS(class_CoinsInfo, "io/anonero/model/CoinsInfo");
    CACHE_REQUIRED_CLASS(class_Pair, "android/util/Pair");

#undef CACHE_REQUIRED_CLASS

    return JNI_VERSION_1_6;
}
#ifdef __cplusplus
}
#endif

int attachJVM(JNIEnv **jenv) {
    int envStat = cachedJVM->GetEnv((void **) jenv, JNI_VERSION_1_6);
    if (envStat == JNI_EDETACHED) {
        if (cachedJVM->AttachCurrentThread(jenv, nullptr) != 0) {
            LOGE("Failed to attach");
            return JNI_ERR;
        }
    } else if (envStat == JNI_EVERSION) {
        LOGE("GetEnv: version not supported");
        return JNI_ERR;
    }
    //LOGI("envStat=%i", envStat);
    return envStat;
}

void detachJVM(JNIEnv *jenv, int envStat) {
    //LOGI("envStat=%i", envStat);
    if (jenv->ExceptionCheck()) {
        jenv->ExceptionDescribe();
        jenv->ExceptionClear();
    }

    if (envStat == JNI_EDETACHED) {
        cachedJVM->DetachCurrentThread();
    }
}

struct MyWalletListener : Monero::WalletListener {
    jobject jlistener;

    MyWalletListener(JNIEnv *env, jobject aListener) {
        LOGD("Created Listener");
        jlistener = env->NewGlobalRef(aListener);;
    }

    ~MyWalletListener() {
        LOGD("Destroyed Listener");
    };

    void deleteGlobalJavaRef(JNIEnv *env) {
        std::lock_guard<std::mutex> lock(_listenerMutex);
        env->DeleteGlobalRef(jlistener);
        jlistener = nullptr;
    }

    /**
 * @brief updated  - generic callback, called when any event (sent/received/block reveived/etc) happened with the wallet;
 */
    void updated() {
        std::lock_guard<std::mutex> lock(_listenerMutex);
        if (jlistener == nullptr) return;
        LOGD("updated");
        JNIEnv *jenv;
        int envStat = attachJVM(&jenv);
        if (envStat == JNI_ERR) return;

        jmethodID listenerClass_updated = jenv->GetMethodID(class_WalletListener, "updated", "()V");
        jenv->CallVoidMethod(jlistener, listenerClass_updated);

        detachJVM(jenv, envStat);
    }


    /**
     * @brief moneySpent - called when money spent
     * @param txId       - transaction id
     * @param amount     - amount
     */
    void moneySpent(const std::string &txId, uint64_t amount) {
        std::lock_guard<std::mutex> lock(_listenerMutex);
        if (jlistener == nullptr) return;
        LOGD("moneySpent %"
                     PRIu64, amount);
    }

    /**
     * @brief moneyReceived - called when money received
     * @param txId          - transaction id
     * @param amount        - amount
     */
    void moneyReceived(const std::string &txId, uint64_t amount) {
        std::lock_guard<std::mutex> lock(_listenerMutex);
        if (jlistener == nullptr) return;
        LOGD("moneyReceived %"
                     PRIu64, amount);
    }

    /**
     * @brief unconfirmedMoneyReceived - called when payment arrived in tx pool
     * @param txId          - transaction id
     * @param amount        - amount
     */
    void unconfirmedMoneyReceived(const std::string &txId, uint64_t amount) {
        std::lock_guard<std::mutex> lock(_listenerMutex);
        if (jlistener == nullptr) return;
        LOGD("unconfirmedMoneyReceived %"
                     PRIu64, amount);
        JNIEnv *jenv;
        int envStat = attachJVM(&jenv);
        if (envStat == JNI_ERR) return;

        jlong amountLong = static_cast<jlong>(amount);
        jstring jstring1 = jenv->NewStringUTF(txId.c_str());

        jmethodID listenerClass_newBlock = jenv->GetMethodID(class_WalletListener,
                                                             "unconfirmedMoneyReceived",
                                                             "(Ljava/lang/String;J)V");
        jenv->CallVoidMethod(jlistener, listenerClass_newBlock, jstring1, amountLong);

        detachJVM(jenv, envStat);
    }

    /**
     * @brief newBlock      - called when new block received
     * @param height        - block height
     */
    void newBlock(uint64_t height) {
        std::lock_guard<std::mutex> lock(_listenerMutex);
        if (jlistener == nullptr) return;
        //LOGD("newBlock");
        JNIEnv *jenv;
        int envStat = attachJVM(&jenv);
        if (envStat == JNI_ERR) return;

        jlong h = static_cast<jlong>(height);
        jmethodID listenerClass_newBlock = jenv->GetMethodID(class_WalletListener, "newBlock",
                                                             "(J)V");
        jenv->CallVoidMethod(jlistener, listenerClass_newBlock, h);

        detachJVM(jenv, envStat);
    }

/**
 * @brief refreshed - called when wallet refreshed by background thread or explicitly refreshed by calling "refresh" synchronously
 */
    void refreshed() {
        std::lock_guard<std::mutex> lock(_listenerMutex);
        if (jlistener == nullptr) return;
        LOGD("refreshed");
        JNIEnv *jenv;

        int envStat = attachJVM(&jenv);
        if (envStat == JNI_ERR) return;

        jmethodID listenerClass_refreshed = jenv->GetMethodID(class_WalletListener, "refreshed",
                                                              "()V");
        jenv->CallVoidMethod(jlistener, listenerClass_refreshed);
        detachJVM(jenv, envStat);
    }
};


//// helper methods
std::vector<std::string> java2cpp(JNIEnv *env, jobject arrayList) {

    jmethodID java_util_ArrayList_size = env->GetMethodID(class_ArrayList, "size", "()I");
    jmethodID java_util_ArrayList_get = env->GetMethodID(class_ArrayList, "get",
                                                         "(I)Ljava/lang/Object;");

    jint len = env->CallIntMethod(arrayList, java_util_ArrayList_size);
    std::vector<std::string> result;
    result.reserve(len);
    for (jint i = 0; i < len; i++) {
        jstring element = static_cast<jstring>(env->CallObjectMethod(arrayList,
                                                                     java_util_ArrayList_get, i));
        ScopedUtfChars utf_pchars(env, element);
    const char *pchars = utf_pchars.get();
        result.emplace_back(pchars);

        env->DeleteLocalRef(element);
    }
    return result;
}

jlong getElement(JNIEnv *env, jlongArray arr_j, int element) {
    jlong result;
    env->GetLongArrayRegion(arr_j, element, 1, &result);
    return result;
}

std::vector<std::uint64_t> java2cpp_long(JNIEnv *env, jlongArray longArray) {
    jint len = env->GetArrayLength(longArray);
    std::vector<std::uint64_t> result;
    result.reserve(len);
    for (jint i = 0; i < len; i++) {
        jlong amount = getElement(env, longArray, i);
        result.emplace_back(amount);
    }
    return result;
}

std::set<std::string> java2cpp_set(JNIEnv *env, jobject arrayList) {

    jmethodID java_util_ArrayList_size = env->GetMethodID(class_ArrayList, "size", "()I");
    jmethodID java_util_ArrayList_get = env->GetMethodID(class_ArrayList, "get",
                                                         "(I)Ljava/lang/Object;");

    jint len = env->CallIntMethod(arrayList, java_util_ArrayList_size);
    std::set<std::string> result;
    for (jint i = 0; i < len; i++) {
        jstring element = static_cast<jstring>(env->CallObjectMethod(arrayList,
                                                                     java_util_ArrayList_get, i));
        ScopedUtfChars utf_pchars(env, element);
    const char *pchars = utf_pchars.get();
        result.emplace(pchars);

        env->DeleteLocalRef(element);
    }
    return result;
}

std::pair<std::string, uint64_t> extract_pair(JNIEnv *env, jobject p) {
    jfieldID first = env->GetFieldID(class_Pair, "first", "Ljava/lang/Object;");
    jfieldID second = env->GetFieldID(class_Pair, "second", "Ljava/lang/Object;");
    if (first == nullptr || second == nullptr) {
        return {};
    }

    jobject stringObject = env->GetObjectField(p, first);
    jobject valueObject = env->GetObjectField(p, second);
    if (stringObject == nullptr || valueObject == nullptr) {
        if (stringObject != nullptr) env->DeleteLocalRef(stringObject);
        if (valueObject != nullptr) env->DeleteLocalRef(valueObject);
        jclass exceptionClass = env->FindClass("java/lang/IllegalArgumentException");
        if (exceptionClass != nullptr) {
            env->ThrowNew(exceptionClass, "Transaction destination and amount must not be null");
            env->DeleteLocalRef(exceptionClass);
        }
        return {};
    }

    auto string = static_cast<jstring>(stringObject);
    ScopedUtfChars utf_chars(env, string);
    const char *chars = utf_chars.get();
    if (chars == nullptr) {
        env->DeleteLocalRef(stringObject);
        env->DeleteLocalRef(valueObject);
        return {};
    }
    std::string convertedString(chars);

    // Pair.second is a boxed java.lang.Long, not a native pointer. Unbox it
    // before passing the amount to Monero's fee estimator.
    jclass longClass = env->FindClass("java/lang/Long");
    if (longClass == nullptr) {
        env->DeleteLocalRef(stringObject);
        env->DeleteLocalRef(valueObject);
        return {};
    }
    jmethodID longValueMethod = env->GetMethodID(longClass, "longValue", "()J");
    if (longValueMethod == nullptr) {
        env->DeleteLocalRef(longClass);
        env->DeleteLocalRef(stringObject);
        env->DeleteLocalRef(valueObject);
        return {};
    }
    jlong boxedValue = env->CallLongMethod(valueObject, longValueMethod);
    env->DeleteLocalRef(longClass);
    env->DeleteLocalRef(stringObject);
    env->DeleteLocalRef(valueObject);
    if (env->ExceptionCheck()) {
        return {};
    }

    return std::make_pair(convertedString, static_cast<uint64_t>(boxedValue));
}


std::vector<std::pair<std::string, uint64_t>> java2cpp_pairvector(JNIEnv *env, jobject arrayList) {

    jmethodID java_util_ArrayList_size = env->GetMethodID(class_ArrayList, "size", "()I");
    jmethodID java_util_ArrayList_get = env->GetMethodID(class_ArrayList, "get",
                                                         "(I)Ljava/lang/Object;");

    jint len = env->CallIntMethod(arrayList, java_util_ArrayList_size);
    std::vector<std::pair<std::string, uint64_t>> result;

    for (jint i = 0; i < len; i++) {
        jobject element = static_cast<jobject>(env->CallObjectMethod(arrayList,
                                                                     java_util_ArrayList_get, i));
        auto pair = extract_pair(env, element);
        result.emplace_back(pair);
    }
    return result;
}

jobject cpp2java(JNIEnv *env, const std::vector<std::string> &vector) {

    jmethodID java_util_ArrayList_ = env->GetMethodID(class_ArrayList, "<init>", "(I)V");
    jmethodID java_util_ArrayList_add = env->GetMethodID(class_ArrayList, "add",
                                                         "(Ljava/lang/Object;)Z");

    jobject result = env->NewObject(class_ArrayList, java_util_ArrayList_,
                                    static_cast<jint> (vector.size()));
    for (const std::string &s: vector) {
        jstring element = env->NewStringUTF(s.c_str());
        env->CallBooleanMethod(result, java_util_ArrayList_add, element);
        env->DeleteLocalRef(element);
    }
    return result;
}

/// end helpers

static void throwWalletCreationError(JNIEnv *env, const char *message) {
    jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message);
        env->DeleteLocalRef(exceptionClass);
    }
}

#ifdef __cplusplus
extern "C"
{
#endif


/**********************************/
/********** WalletManager *********/
/**********************************/
JNIEXPORT jlong JNICALL
Java_io_anonero_model_WalletManager_createWalletJ(JNIEnv *env, jobject instance,
                                                  jstring path,
                                                  jstring password,
                                                  jstring passpharse,
                                                  jstring language,
                                                  jint networkType) {
    std::string seed_words;
    std::string err;
    bool _polyseedCreate = Monero::Wallet::createPolyseed(seed_words, err);
    if (!_polyseedCreate) {
        LOGE("Failed to polyseedCreate");
        jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
        if (exceptionClass != nullptr) {
            env->ThrowNew(exceptionClass, err.empty() ? "Failed to generate a Polyseed" : err.c_str());
        }
        return 0;
    }

//     wallet->createPolyseed(seed_words, err);

    ScopedUtfChars utf__path(env, path);
    const char *_path = utf__path.get();
    ScopedUtfChars utf__password(env, password);
    const char *_password = utf__password.get();
    ScopedUtfChars utf__passpharse(env, passpharse);
    const char *_passpharse = utf__passpharse.get();
    ScopedUtfChars utf__language(env, language);
    const char *_language = utf__language.get(); // NOT USED ANYMORE?????
    Monero::NetworkType _networkType = static_cast<Monero::NetworkType>(networkType);
    Monero::Wallet *wallet =
            Monero::WalletManagerFactory::getWalletManager()->createWalletFromPolyseed(
                    std::string(_path),
                    std::string(_password),
                    _networkType,
                    seed_words,
                    std::string(_passpharse), true);

    if (wallet == nullptr) {




        throwWalletCreationError(env, "Native wallet creation returned null");
        return 0;
    }
    bool setupStatus = wallet->setupBackgroundSync(Monero::Wallet::BackgroundSync_ReusePassword,
                                                   std::string(_password), {});
    if (setupStatus == true) {
        LOGD("createWalletJ(): setupBackgroundSync(): success!");
    } else {
        LOGD("createWalletJ(): setupBackgroundSync(): failure!");
    }





    return reinterpret_cast<jlong>(wallet);
}

JNIEXPORT jlong JNICALL
Java_io_anonero_model_WalletManager_openWalletJ(JNIEnv *env, jobject instance,
                                                jstring path, jstring password,
                                                jint networkType, jboolean viewOnly) {
    LOGD("openWalletJ(): start");
    ScopedUtfChars utf__path(env, path);
    const char *_path = utf__path.get();
    ScopedUtfChars utf__password(env, password);
    const char *_password = utf__password.get();
    Monero::NetworkType _networkType = static_cast<Monero::NetworkType>(networkType);
    Monero::Wallet *wallet =
            Monero::WalletManagerFactory::getWalletManager()->openWallet(
                    std::string(_path),
                    std::string(_password),
                    _networkType);

    if (wallet == nullptr) {


        throwWalletCreationError(env, "Unable to open wallet");
        return 0;
    }
    if (!viewOnly) {
        // setup background sync
        bool setupStatus = wallet->setupBackgroundSync(Monero::Wallet::BackgroundSync_ReusePassword,
                                                       std::string(_password), {});
        if (setupStatus == true) {
            LOGD("openWalletJ(): setupBackgroundSync(): success!");
        } else {
            LOGD("openWalletJ(): setupBackgroundSync(): failure!");
        }
    }


    return reinterpret_cast<jlong>(wallet);
}

JNIEXPORT jlong JNICALL
Java_io_anonero_model_WalletManager_recoveryWalletJ(JNIEnv *env, jobject instance,
                                                    jstring path, jstring password,
                                                    jstring mnemonic, jstring offset,
                                                    jint networkType,
                                                    jlong restoreHeight) {
    ScopedUtfChars utf__path(env, path);
    const char *_path = utf__path.get();
    ScopedUtfChars utf__password(env, password);
    const char *_password = utf__password.get();
    ScopedUtfChars utf__mnemonic(env, mnemonic);
    const char *_mnemonic = utf__mnemonic.get();
    ScopedUtfChars utf__offset(env, offset);
    const char *_offset = utf__offset.get();
    Monero::NetworkType _networkType = static_cast<Monero::NetworkType>(networkType);

    Monero::Wallet *wallet =
            Monero::WalletManagerFactory::getWalletManager()->recoveryWallet(
                    std::string(_path),
                    std::string(_password),
                    std::string(_mnemonic),
                    _networkType,
                    (uint64_t) restoreHeight,
                    1, // kdf_rounds
                    std::string(_offset));
    if (wallet == nullptr) {




        throwWalletCreationError(env, "Unable to restore wallet from Monero seed");
        return 0;
    }
    bool setupStatus = wallet->setupBackgroundSync(Monero::Wallet::BackgroundSync_ReusePassword,
                                                   std::string(_password), {});
    if (setupStatus == true) {
        LOGD("recoveryWalletJ(): setupBackgroundSync(): success!");
    } else {
        LOGD("recoveryWalletJ(): setupBackgroundSync(): failure!");
    }




    return reinterpret_cast<jlong>(wallet);
}

JNIEXPORT jlong JNICALL
Java_io_anonero_model_WalletManager_recoveryWalletPolyseedJ(JNIEnv *env, jobject instance,
                                                            jstring path, jstring password,
                                                            jstring mnemonic, jstring offset,
                                                            jint networkType) {
    ScopedUtfChars utf__path(env, path);
    const char *_path = utf__path.get();
    ScopedUtfChars utf__password(env, password);
    const char *_password = utf__password.get();
    ScopedUtfChars utf__mnemonic(env, mnemonic);
    const char *_mnemonic = utf__mnemonic.get();
    ScopedUtfChars utf__offset(env, offset);
    const char *_offset = utf__offset.get();
    Monero::NetworkType _networkType = static_cast<Monero::NetworkType>(networkType);

    Monero::Wallet *wallet =
            Monero::WalletManagerFactory::getWalletManager()->createWalletFromPolyseed(
                    std::string(_path),
                    std::string(_password),
                    _networkType,
                    std::string(_mnemonic),
                    std::string(_offset), false);
    if (wallet == nullptr) {




        throwWalletCreationError(env, "Unable to restore wallet from Polyseed");
        return 0;
    }
    bool setupStatus = wallet->setupBackgroundSync(Monero::Wallet::BackgroundSync_ReusePassword,
                                                   std::string(_password), {});
    if (setupStatus == true) {
        LOGD("recoveryWalletPolyseedJ(): setupBackgroundSync(): success!");
    } else {
        LOGD("recoveryWalletPolyseedJ(): setupBackgroundSync(): failure!");
    }




    return reinterpret_cast<jlong>(wallet);
}

JNIEXPORT jlong JNICALL
Java_io_anonero_model_WalletManager_createWalletFromKeysJ(JNIEnv *env, jobject instance,
                                                          jstring path, jstring password,
                                                          jstring language,
                                                          jint networkType,
                                                          jlong restoreHeight,
                                                          jstring addressString,
                                                          jstring viewKeyString,
                                                          jstring spendKeyString) {
    ScopedUtfChars utf__path(env, path);
    const char *_path = utf__path.get();
    ScopedUtfChars utf__password(env, password);
    const char *_password = utf__password.get();
    ScopedUtfChars utf__language(env, language);
    const char *_language = utf__language.get();
    Monero::NetworkType _networkType = static_cast<Monero::NetworkType>(networkType);
    ScopedUtfChars utf__addressString(env, addressString);
    const char *_addressString = utf__addressString.get();
    ScopedUtfChars utf__viewKeyString(env, viewKeyString);
    const char *_viewKeyString = utf__viewKeyString.get();
    ScopedUtfChars utf__spendKeyString(env, spendKeyString);
    const char *_spendKeyString = utf__spendKeyString.get();

    Monero::Wallet *wallet =
            Monero::WalletManagerFactory::getWalletManager()->createWalletFromKeys(
                    std::string(_path),
                    std::string(_password),
                    std::string(_language),
                    _networkType,
                    (uint64_t) restoreHeight,
                    std::string(_addressString),
                    std::string(_viewKeyString),
                    std::string(_spendKeyString));
    if (wallet == nullptr) {






        throwWalletCreationError(env, "Unable to create wallet from keys");
        return 0;
    }
    bool setupStatus = wallet->setupBackgroundSync(Monero::Wallet::BackgroundSync_ReusePassword,
                                                   std::string(_password), {});
    if (setupStatus == true) {
        LOGD("createWalletFromKeysJ(): setupBackgroundSync(): success!");
    } else {
        LOGD("createWalletFromKeysJ(): setupBackgroundSync(): failure!");
    }






    return reinterpret_cast<jlong>(wallet);
}


// virtual void setSubaddressLookahead(uint32_t major, uint32_t minor) = 0;

JNIEXPORT jlong JNICALL
Java_io_anonero_model_WalletManager_createWalletFromDeviceJ(JNIEnv *env, jobject instance,
                                                            jstring path,
                                                            jstring password,
                                                            jint networkType,
                                                            jstring deviceName,
                                                            jlong restoreHeight,
                                                            jstring subaddressLookahead) {
    ScopedUtfChars utf__path(env, path);
    const char *_path = utf__path.get();
    ScopedUtfChars utf__password(env, password);
    const char *_password = utf__password.get();
    Monero::NetworkType _networkType = static_cast<Monero::NetworkType>(networkType);
    ScopedUtfChars utf__deviceName(env, deviceName);
    const char *_deviceName = utf__deviceName.get();
    ScopedUtfChars utf__subaddressLookahead(env, subaddressLookahead);
    const char *_subaddressLookahead = utf__subaddressLookahead.get();

    Monero::Wallet *wallet =
            Monero::WalletManagerFactory::getWalletManager()->createWalletFromDevice(
                    std::string(_path),
                    std::string(_password),
                    _networkType,
                    std::string(_deviceName),
                    (uint64_t) restoreHeight,
                    std::string(_subaddressLookahead));
    if (wallet == nullptr) {




        throwWalletCreationError(env, "Unable to create wallet from device");
        return 0;
    }
    bool setupStatus = wallet->setupBackgroundSync(Monero::Wallet::BackgroundSync_ReusePassword,
                                                   std::string(_password), {});
    if (setupStatus == true) {
        LOGD("createWalletFromDeviceJ(): setupBackgroundSync(): success!");
    } else {
        LOGD("createWalletFromDeviceJ(): setupBackgroundSync(): failure!");
    }




    return reinterpret_cast<jlong>(wallet);
}

JNIEXPORT jboolean JNICALL
Java_io_anonero_model_WalletManager_walletExists(JNIEnv *env, jobject instance,
                                                 jstring path) {
    ScopedUtfChars utf__path(env, path);
    const char *_path = utf__path.get();
    bool exists =
            Monero::WalletManagerFactory::getWalletManager()->walletExists(std::string(_path));

    return static_cast<jboolean>(exists);
}

JNIEXPORT jboolean JNICALL
Java_io_anonero_model_WalletManager_verifyWalletPassword(JNIEnv *env, jobject instance,
                                                         jstring keys_file_name,
                                                         jstring password,
                                                         jboolean watch_only) {
    ScopedUtfChars utf__keys_file_name(env, keys_file_name);
    const char *_keys_file_name = utf__keys_file_name.get();
    ScopedUtfChars utf__password(env, password);
    const char *_password = utf__password.get();
    bool passwordOk =
            Monero::WalletManagerFactory::getWalletManager()->verifyWalletPassword(
                    std::string(_keys_file_name), std::string(_password), watch_only);


    return static_cast<jboolean>(passwordOk);
}

//virtual int queryWalletHardware(const std::string &keys_file_name, const std::string &password) const = 0;
JNIEXPORT jint JNICALL
Java_io_anonero_model_WalletManager_queryWalletDeviceJ(JNIEnv *env, jobject instance,
                                                       jstring keys_file_name,
                                                       jstring password) {
    ScopedUtfChars utf__keys_file_name(env, keys_file_name);
    const char *_keys_file_name = utf__keys_file_name.get();
    ScopedUtfChars utf__password(env, password);
    const char *_password = utf__password.get();
    Monero::Wallet::Device device_type;
    bool ok = Monero::WalletManagerFactory::getWalletManager()->
            queryWalletDevice(device_type, std::string(_keys_file_name), std::string(_password));


    if (ok)
        return static_cast<jint>(device_type);
    else
        return -1;
}

JNIEXPORT jobject JNICALL
Java_io_anonero_model_WalletManager_findWallets(JNIEnv *env, jobject instance,
                                                jstring path) {
    ScopedUtfChars utf__path(env, path);
    const char *_path = utf__path.get();
    std::vector<std::string> walletPaths =
            Monero::WalletManagerFactory::getWalletManager()->findWallets(std::string(_path));

    return cpp2java(env, walletPaths);
}

//TODO virtual bool checkPayment(const std::string &address, const std::string &txid, const std::string &txkey, const std::string &daemon_address, uint64_t &received, uint64_t &height, std::string &error) const = 0;

JNIEXPORT void JNICALL
Java_io_anonero_model_WalletManager_setDaemonAddressJ(JNIEnv *env, jobject instance,
                                                      jstring address) {
    ScopedUtfChars utf__address(env, address);
    const char *_address = utf__address.get();
    LOGD("setDaemonAddressJ(): start");
    Monero::WalletManagerFactory::getWalletManager()->setDaemonAddress(std::string(_address));
    LOGD("setDaemonAddressJ(): end");

}

// returns whether the daemon can be reached, and its version number
JNIEXPORT jint JNICALL
Java_io_anonero_model_WalletManager_getDaemonVersion(JNIEnv *env,
                                                     jobject instance) {
    uint32_t version;
    bool isConnected =
            Monero::WalletManagerFactory::getWalletManager()->connected(&version);
    if (!isConnected) version = 0;
    return version;
}

JNIEXPORT jlong JNICALL
Java_io_anonero_model_WalletManager_getBlockchainHeight(JNIEnv *env, jobject instance) {
    return Monero::WalletManagerFactory::getWalletManager()->blockchainHeight();
}

JNIEXPORT jlong JNICALL
Java_io_anonero_model_WalletManager_getBlockchainTargetHeight(JNIEnv *env,
                                                              jobject instance) {
    return Monero::WalletManagerFactory::getWalletManager()->blockchainTargetHeight();
}

JNIEXPORT jlong JNICALL
Java_io_anonero_model_WalletManager_getNetworkDifficulty(JNIEnv *env, jobject instance) {
    return Monero::WalletManagerFactory::getWalletManager()->networkDifficulty();
}

JNIEXPORT jdouble JNICALL
Java_io_anonero_model_WalletManager_getMiningHashRate(JNIEnv *env, jobject instance) {
    return Monero::WalletManagerFactory::getWalletManager()->miningHashRate();
}

JNIEXPORT jlong JNICALL
Java_io_anonero_model_WalletManager_getBlockTarget(JNIEnv *env, jobject instance) {
    return Monero::WalletManagerFactory::getWalletManager()->blockTarget();
}

JNIEXPORT jboolean JNICALL
Java_io_anonero_model_WalletManager_isMining(JNIEnv *env, jobject instance) {
    return static_cast<jboolean>(Monero::WalletManagerFactory::getWalletManager()->isMining());
}

JNIEXPORT jboolean JNICALL
Java_io_anonero_model_WalletManager_startMining(JNIEnv *env, jobject instance,
                                                jstring address,
                                                jboolean background_mining,
                                                jboolean ignore_battery) {
    ScopedUtfChars utf__address(env, address);
    const char *_address = utf__address.get();
    bool success =
            Monero::WalletManagerFactory::getWalletManager()->startMining(std::string(_address),
                                                                          background_mining,
                                                                          ignore_battery);

    return static_cast<jboolean>(success);
}

JNIEXPORT jboolean JNICALL
Java_io_anonero_model_WalletManager_stopMining(JNIEnv *env, jobject instance) {
    return static_cast<jboolean>(Monero::WalletManagerFactory::getWalletManager()->stopMining());
}

JNIEXPORT jstring JNICALL
Java_io_anonero_model_WalletManager_resolveOpenAlias(JNIEnv *env, jobject instance,
                                                     jstring address,
                                                     jboolean dnssec_valid) {
    ScopedUtfChars utf__address(env, address);
    const char *_address = utf__address.get();
    bool _dnssec_valid = (bool) dnssec_valid;
    std::string resolvedAlias =
            Monero::WalletManagerFactory::getWalletManager()->resolveOpenAlias(
                    std::string(_address),
                    _dnssec_valid);

    return env->NewStringUTF(resolvedAlias.c_str());
}

JNIEXPORT jboolean JNICALL
Java_io_anonero_model_WalletManager_setProxyJ(JNIEnv *env, jobject instance,
                                              jstring address) {
    ScopedUtfChars utf__address(env, address);
    const char *_address = utf__address.get();
    bool rc =
            Monero::WalletManagerFactory::getWalletManager()->setProxy(std::string(_address));

    return rc;
}


//TODO static std::tuple<bool, std::string, std::string, std::string, std::string> checkUpdates(const std::string &software, const std::string &subdir);

JNIEXPORT jboolean JNICALL
Java_io_anonero_model_WalletManager_closeJ(JNIEnv *env, jobject instance,
                                           jobject walletInstance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, walletInstance);
    bool closeSuccess = Monero::WalletManagerFactory::getWalletManager()->closeWallet(wallet,
                                                                                      false);
    if (closeSuccess) {
        MyWalletListener *walletListener = getHandle<MyWalletListener>(env, walletInstance,
                                                                       "listenerHandle");
        if (walletListener != nullptr) {
            walletListener->deleteGlobalJavaRef(env);
            delete walletListener;
        }
    }
    LOGD("wallet closed");
    return static_cast<jboolean>(closeSuccess);
}




/**********************************/
/************ Wallet **************/
/**********************************/

// For backwards compatibility reasons, getSeed will continue silently fallback to legacy mnemonic
// seed.
// Since the changes aren't upstremead yet, and we don't know how polyseed will evolve / if there
// will be any changes we wouldn't like to have the users stuck with not working wallet that they
// can't backup.
JNIEXPORT jstring JNICALL
Java_io_anonero_model_Wallet_getSeed(JNIEnv *env, jobject instance, jstring seedOffset) {
    ScopedUtfChars utf__seedOffset(env, seedOffset);
    const char *_seedOffset = utf__seedOffset.get();
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);

    std::string _seed;
    std::string seedOffsetStr(_seedOffset);
    bool _getPolyseed = wallet->getPolyseed(_seed, seedOffsetStr);

    if (_getPolyseed) {
        LOGD("_getPolyseed: true");
    } else {
        LOGD("_getPolyseed: false");
        LOGE("wallet->getSeed() %s", wallet->errorString().c_str());
        LOGD("Falling back to normal, boring, seed");
        _seed = wallet->seed(std::string(_seedOffset));
    }


    jstring seed = env->NewStringUTF(_seed.c_str());
    return seed;
}

JNIEXPORT jstring JNICALL
Java_io_anonero_model_Wallet_getLegacySeed(JNIEnv *env, jobject instance, jstring seedOffset) {
    ScopedUtfChars utf__seedOffset(env, seedOffset);
    const char *_seedOffset = utf__seedOffset.get();
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);

    std::string _seed;
    std::string seedOffsetStr(_seedOffset);
    _seed = wallet->seed(std::string(_seedOffset));


    jstring seed = env->NewStringUTF(_seed.c_str());
    return seed;
}

JNIEXPORT jboolean JNICALL
Java_io_anonero_model_Wallet_isPolyseedSupported(JNIEnv *env, jobject instance,
                                                 jstring seedOffset) {
    ScopedUtfChars utf__seedOffset(env, seedOffset);
    const char *_seedOffset = utf__seedOffset.get();
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);

    std::string _seed;
    std::string seedOffsetStr(_seedOffset);
    bool _getPolyseed = wallet->getPolyseed(_seed, seedOffsetStr);


    return _getPolyseed;
}


JNIEXPORT jstring JNICALL
Java_io_anonero_model_Wallet_getSeedLanguage(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return env->NewStringUTF(wallet->getSeedLanguage().c_str());
}

JNIEXPORT void JNICALL
Java_io_anonero_model_Wallet_setSeedLanguage(JNIEnv *env, jobject instance,
                                             jstring language) {
    ScopedUtfChars utf__language(env, language);
    const char *_language = utf__language.get();
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    wallet->setSeedLanguage(std::string(_language));

}

JNIEXPORT jint JNICALL
Java_io_anonero_model_Wallet_getStatusJ(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return wallet->status();
}

jobject newWalletStatusInstance(JNIEnv *env, int status, const std::string &errorString) {
    jmethodID init = env->GetMethodID(class_WalletStatus, "<init>",
                                      "(ILjava/lang/String;)V");
    jstring _errorString = env->NewStringUTF(errorString.c_str());
    jobject instance = env->NewObject(class_WalletStatus, init, status, _errorString);
    env->DeleteLocalRef(_errorString);
    return instance;
}


JNIEXPORT jobject JNICALL
Java_io_anonero_model_Wallet_statusWithErrorString(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);

    int status;
    std::string errorString;
    wallet->statusWithErrorString(status, errorString);

    return newWalletStatusInstance(env, status, errorString);
}

JNIEXPORT jboolean JNICALL
Java_io_anonero_model_Wallet_setPassword(JNIEnv *env, jobject instance,
                                         jstring password) {
    ScopedUtfChars utf__password(env, password);
    const char *_password = utf__password.get();
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    bool success = wallet->setPassword(std::string(_password));

    return static_cast<jboolean>(success);
}

JNIEXPORT jstring JNICALL
Java_io_anonero_model_Wallet_getAddressJ(JNIEnv *env, jobject instance,
                                         jint accountIndex,
                                         jint addressIndex) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return env->NewStringUTF(
            wallet->address((uint32_t) accountIndex, (uint32_t) addressIndex).c_str());
}

JNIEXPORT jstring JNICALL
Java_io_anonero_model_Wallet_getPath(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return env->NewStringUTF(wallet->path().c_str());
}

JNIEXPORT jint JNICALL
Java_io_anonero_model_Wallet_nettype(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return wallet->nettype();
}

//TODO virtual void hardForkInfo(uint8_t &version, uint64_t &earliest_height) const = 0;
//TODO virtual bool useForkRules(uint8_t version, int64_t early_blocks) const = 0;

JNIEXPORT jstring JNICALL
Java_io_anonero_model_Wallet_getIntegratedAddress(JNIEnv *env, jobject instance,
                                                  jstring payment_id) {
    ScopedUtfChars utf__payment_id(env, payment_id);
    const char *_payment_id = utf__payment_id.get();
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    std::string address = wallet->integratedAddress(_payment_id);

    return env->NewStringUTF(address.c_str());
}

JNIEXPORT jstring JNICALL
Java_io_anonero_model_Wallet_getSecretViewKey(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return env->NewStringUTF(wallet->secretViewKey().c_str());
}

JNIEXPORT jstring JNICALL
Java_io_anonero_model_Wallet_getSecretSpendKey(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return env->NewStringUTF(wallet->secretSpendKey().c_str());
}

JNIEXPORT jboolean JNICALL
Java_io_anonero_model_Wallet_store(JNIEnv *env, jobject instance,
                                   jstring path) {
    ScopedUtfChars utf__path(env, path);
    const char *_path = utf__path.get();
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    bool success = wallet->store(std::string(_path));
    if (!success) {
        LOGE("store(): %s", wallet->errorString().c_str());
    }

    return static_cast<jboolean>(success);
}

JNIEXPORT jstring JNICALL
Java_io_anonero_model_Wallet_getFilename(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return env->NewStringUTF(wallet->filename().c_str());
}

//    virtual std::string keysFilename() const = 0;

JNIEXPORT jboolean JNICALL
Java_io_anonero_model_Wallet_initJ(JNIEnv *env, jobject instance,
                                   jstring daemon_address,
                                   jlong upper_transaction_size_limit,
                                   jstring daemon_username, jstring daemon_password,
                                   jstring proxy_address) {
    ScopedUtfChars utf__daemon_address(env, daemon_address);
    const char *_daemon_address = utf__daemon_address.get();
    ScopedUtfChars utf__daemon_username(env, daemon_username);
    const char *_daemon_username = utf__daemon_username.get();
    ScopedUtfChars utf__daemon_password(env, daemon_password);
    const char *_daemon_password = utf__daemon_password.get();
    ScopedUtfChars utf__proxy_address(env, proxy_address);
    const char *_proxy_address = utf__proxy_address.get();
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    LOGD("initJ(): start");
    bool status = wallet->init(_daemon_address, (uint64_t) upper_transaction_size_limit,
                               _daemon_username,
                               _daemon_password,
                               false,
                               false,
                               _proxy_address);




    LOGD("initJ(): end");
    return static_cast<jboolean>(status);
}

//    virtual bool createWatchOnly(const std::string &path, const std::string &password, const std::string &language) const = 0;

JNIEXPORT void JNICALL
Java_io_anonero_model_Wallet_setRestoreHeight(JNIEnv *env, jobject instance,
                                              jlong height) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    wallet->setRefreshFromBlockHeight((uint64_t) height);
}

JNIEXPORT jlong JNICALL
Java_io_anonero_model_Wallet_getRestoreHeight(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return wallet->getRefreshFromBlockHeight();
}

//    virtual void setRecoveringFromSeed(bool recoveringFromSeed) = 0;
//    virtual bool connectToDaemon() = 0;

JNIEXPORT jint JNICALL
Java_io_anonero_model_Wallet_getConnectionStatusJ(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return wallet->connected();
}
//TODO virtual void setTrustedDaemon(bool arg) = 0;
JNIEXPORT jboolean JNICALL
Java_io_anonero_model_Wallet_setTrustedDaemon(JNIEnv *env, jobject instance, jboolean arg) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    wallet->setTrustedDaemon(arg);
    return static_cast<jboolean>(true);
}


// TODO virtual bool trustedDaemon() const = 0;

JNIEXPORT jboolean JNICALL
Java_io_anonero_model_Wallet_setProxyJ(JNIEnv *env, jobject instance,
                                       jstring address) {
    ScopedUtfChars utf__address(env, address);
    const char *_address = utf__address.get();
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    bool rc = wallet->setProxy(std::string(_address));

    return rc;
}

JNIEXPORT jlong JNICALL
Java_io_anonero_model_Wallet_getBalance(JNIEnv *env, jobject instance,
                                        jint accountIndex) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return wallet->balance((uint32_t) accountIndex);
}

JNIEXPORT jlong JNICALL
Java_io_anonero_model_Wallet_getBalanceAll(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return wallet->balanceAll();
}

JNIEXPORT jlong JNICALL
Java_io_anonero_model_Wallet_getUnlockedBalance(JNIEnv *env, jobject instance,
                                                jint accountIndex) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return wallet->unlockedBalance((uint32_t) accountIndex);
}

JNIEXPORT jlong JNICALL
Java_io_anonero_model_Wallet_getUnlockedBalanceAll(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return wallet->unlockedBalanceAll();
}

JNIEXPORT jboolean JNICALL
Java_io_anonero_model_Wallet_isWatchOnly(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return static_cast<jboolean>(wallet->watchOnly());
}

JNIEXPORT jlong JNICALL
Java_io_anonero_model_Wallet_getBlockChainHeight(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return wallet->blockChainHeight();
}

JNIEXPORT jlong JNICALL
Java_io_anonero_model_Wallet_getApproximateBlockChainHeight(JNIEnv *env,
                                                            jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return wallet->approximateBlockChainHeight();
}

JNIEXPORT jlong JNICALL
Java_io_anonero_model_Wallet_getDaemonBlockChainHeight(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return wallet->daemonBlockChainHeight();
}

JNIEXPORT jlong JNICALL
Java_io_anonero_model_Wallet_getDaemonBlockChainTargetHeight(JNIEnv *env,
                                                             jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return wallet->daemonBlockChainTargetHeight();
}

JNIEXPORT jboolean JNICALL
Java_io_anonero_model_Wallet_isSynchronizedJ(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return static_cast<jboolean>(wallet->synchronized());
}

JNIEXPORT jint JNICALL
Java_io_anonero_model_Wallet_getDeviceTypeJ(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    Monero::Wallet::Device device_type = wallet->getDeviceType();
    return static_cast<jint>(device_type);
}

//void cn_slow_hash(const void *data, size_t length, char *hash); // from crypto/hash-ops.h
JNIEXPORT jbyteArray JNICALL
Java_io_anonero_util_KeyStoreHelper_slowHash(JNIEnv *env, jclass clazz,
                                             jbyteArray data, jint brokenVariant) {
    char hash[HASH_SIZE];
    jsize size = env->GetArrayLength(data);
    if ((brokenVariant > 0) && (size < 200 /*sizeof(union hash_state)*/)) {
        return nullptr;
    }

    jbyte *buffer = env->GetByteArrayElements(data, nullptr);
    switch (brokenVariant) {
        case 1:
            slow_hash_broken(buffer, hash, 1);
            break;
        case 2:
            slow_hash_broken(buffer, hash, 0);
            break;
        default: // not broken
            slow_hash(buffer, (size_t) size, hash);
    }
    env->ReleaseByteArrayElements(data, buffer, JNI_ABORT); // do not update java byte[]
    jbyteArray result = env->NewByteArray(HASH_SIZE);
    env->SetByteArrayRegion(result, 0, HASH_SIZE, (jbyte *) hash);
    return result;
}

JNIEXPORT jstring JNICALL
Java_io_anonero_model_Wallet_getDisplayAmount(JNIEnv *env, jclass clazz,
                                              jlong amount) {
    return env->NewStringUTF(Monero::Wallet::displayAmount(amount).c_str());
}

JNIEXPORT jlong JNICALL
Java_io_anonero_model_Wallet_getAmountFromString(JNIEnv *env, jclass clazz,
                                                 jstring amount) {
    ScopedUtfChars utf__amount(env, amount);
    const char *_amount = utf__amount.get();
    uint64_t x = Monero::Wallet::amountFromString(_amount);

    return x;
}

JNIEXPORT jlong JNICALL
Java_io_anonero_model_Wallet_getAmountFromDouble(JNIEnv *env, jclass clazz,
                                                 jdouble amount) {
    return Monero::Wallet::amountFromDouble(amount);
}

JNIEXPORT jstring JNICALL
Java_io_anonero_model_Wallet_generatePaymentId(JNIEnv *env, jclass clazz) {
    return env->NewStringUTF(Monero::Wallet::genPaymentId().c_str());
}

JNIEXPORT jboolean JNICALL
Java_io_anonero_model_Wallet_isPaymentIdValid(JNIEnv *env, jclass clazz,
                                              jstring payment_id) {
    ScopedUtfChars utf__payment_id(env, payment_id);
    const char *_payment_id = utf__payment_id.get();
    bool isValid = Monero::Wallet::paymentIdValid(_payment_id);

    return static_cast<jboolean>(isValid);
}

JNIEXPORT jboolean JNICALL
Java_io_anonero_model_Wallet_isAddressValid(JNIEnv *env, jclass clazz,
                                            jstring address, jint networkType) {
    ScopedUtfChars utf__address(env, address);
    const char *_address = utf__address.get();
    Monero::NetworkType _networkType = static_cast<Monero::NetworkType>(networkType);
    bool isValid = Monero::Wallet::addressValid(_address, _networkType);

    return static_cast<jboolean>(isValid);
}

JNIEXPORT jstring JNICALL
Java_io_anonero_model_Wallet_getPaymentIdFromAddress(JNIEnv *env, jclass clazz,
                                                     jstring address,
                                                     jint networkType) {
    Monero::NetworkType _networkType = static_cast<Monero::NetworkType>(networkType);
    ScopedUtfChars utf__address(env, address);
    const char *_address = utf__address.get();
    std::string payment_id = Monero::Wallet::paymentIdFromAddress(_address, _networkType);

    return env->NewStringUTF(payment_id.c_str());
}

JNIEXPORT jlong JNICALL
Java_io_anonero_model_Wallet_getMaximumAllowedAmount(JNIEnv *env, jclass clazz) {
    return Monero::Wallet::maximumAllowedAmount();
}

JNIEXPORT void JNICALL
Java_io_anonero_model_Wallet_startRefresh(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    wallet->startRefresh();
}

JNIEXPORT void JNICALL
Java_io_anonero_model_Wallet_pauseRefresh(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    wallet->pauseRefresh();
}

JNIEXPORT jboolean JNICALL
Java_io_anonero_model_Wallet_startBackgroundSync(JNIEnv *env, jobject instance) {
    LOGD("startBackgroundSync(): start");
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);

    bool bgsyncStatus = wallet->startBackgroundSync();
    if (bgsyncStatus == true) {
        LOGD("startBackgroundSync(): end: success!");
    } else {
        LOGD("startBackgroundSync(): end: fail!");
    }
    return static_cast<jboolean>(bgsyncStatus);
}

JNIEXPORT jboolean JNICALL
Java_io_anonero_model_Wallet_stopBackgroundSync(JNIEnv *env, jobject instance, jstring password) {
    LOGD("stopBackgroundSync(): start");
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    ScopedUtfChars utf__password(env, password);
    const char *_password = utf__password.get();
    bool bgsyncStatus = wallet->stopBackgroundSync(std::string(_password));
    if (bgsyncStatus == true) {
        LOGD("stopBackgroundSync(): end: success!");
    } else {
        LOGD("stopBackgroundSync(): end: fail!");
    }

    return static_cast<jboolean>(bgsyncStatus);
}

JNIEXPORT jboolean JNICALL
Java_io_anonero_model_Wallet_refresh(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return static_cast<jboolean>(wallet->refresh());
}

JNIEXPORT void JNICALL
Java_io_anonero_model_Wallet_refreshAsync(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    wallet->refreshAsync();
}

//TODO virtual bool rescanBlockchain() = 0;

//virtual void rescanBlockchainAsync() = 0;
JNIEXPORT void JNICALL
Java_io_anonero_model_Wallet_rescanBlockchainAsyncJ(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    wallet->rescanBlockchainAsync();
}


//TODO virtual void setAutoRefreshInterval(int millis) = 0;
//TODO virtual int autoRefreshInterval() const = 0;

JNIEXPORT jlong JNICALL
Java_io_anonero_model_Wallet_createTransactionJ(JNIEnv *env, jobject instance,
                                                jstring dst_addr, jstring payment_id,
                                                jlong amount, jint mixin_count,
                                                jint priority,
                                                jint accountIndex, jobject key_images) {
    const std::set<std::string> _key_images = java2cpp_set(env, key_images);
    ScopedUtfChars utf__dst_addr(env, dst_addr);
    const char *_dst_addr = utf__dst_addr.get();
    ScopedUtfChars utf__payment_id(env, payment_id);
    const char *_payment_id = utf__payment_id.get();
    Monero::PendingTransaction::Priority _priority =
            static_cast<Monero::PendingTransaction::Priority>(priority);
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);

    Monero::PendingTransaction *tx = wallet->createTransaction(_dst_addr, _payment_id,
                                                               amount, (uint32_t) mixin_count,
                                                               _priority,
                                                               (uint32_t) accountIndex, {},
                                                               _key_images);



    return reinterpret_cast<jlong>(tx);
}

JNIEXPORT jlong JNICALL
Java_io_anonero_model_Wallet_estimateTransactionFee(JNIEnv *env, jobject instance,
                                                    jobject destinations, jint priority) {
    std::vector<std::pair<std::string, uint64_t>> dest_vector = java2cpp_pairvector(env,
                                                                                    destinations);
    Monero::PendingTransaction::Priority _priority = static_cast<Monero::PendingTransaction::Priority>(priority);
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    uint64_t fee = wallet->estimateTransactionFee(dest_vector, _priority);
    return fee;
}

JNIEXPORT jlong JNICALL
Java_io_anonero_model_Wallet_createSweepTransaction(JNIEnv *env, jobject instance,
                                                    jstring dst_addr, jstring payment_id,
                                                    jint mixin_count,
                                                    jint priority,
                                                    jint accountIndex,
                                                    jobject key_images) {

    const std::set<std::string> _key_images = java2cpp_set(env, key_images);
    ScopedUtfChars utf__dst_addr(env, dst_addr);
    const char *_dst_addr = utf__dst_addr.get();
    ScopedUtfChars utf__payment_id(env, payment_id);
    const char *_payment_id = utf__payment_id.get();
    Monero::PendingTransaction::Priority _priority =
            static_cast<Monero::PendingTransaction::Priority>(priority);
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);

    Monero::optional<uint64_t> empty;

    Monero::PendingTransaction *tx = wallet->createTransaction(_dst_addr, _payment_id,
                                                               empty, (uint32_t) mixin_count,
                                                               _priority,
                                                               (uint32_t) accountIndex, {},
                                                               _key_images);


    return reinterpret_cast<jlong>(tx);
}

JNIEXPORT jlong JNICALL
Java_io_anonero_model_Wallet_createSweepUnmixableTransactionJ(JNIEnv *env,
                                                              jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    Monero::PendingTransaction *tx = wallet->createSweepUnmixableTransaction();
    return reinterpret_cast<jlong>(tx);
}

//virtual UnsignedTransaction * loadUnsignedTx(const std::string &unsigned_filename) = 0;
//virtual bool submitTransaction(const std::string &fileName) = 0;

JNIEXPORT void JNICALL
Java_io_anonero_model_Wallet_disposeTransaction(JNIEnv *env, jobject instance,
                                                jobject pendingTransaction) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    Monero::PendingTransaction *_pendingTransaction =
            getHandle<Monero::PendingTransaction>(env, pendingTransaction);
    wallet->disposeTransaction(_pendingTransaction);
}

//virtual bool exportKeyIexportKeyImagesmages(const std::string &filename, bool all = false) = 0;
JNIEXPORT jboolean JNICALL
Java_io_anonero_model_Wallet_exportKeyImages(JNIEnv *env, jobject instance, jstring filename,
                                             jboolean all) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    LOGD("monerujo.cpp: Exporting key images");
    ScopedUtfChars utf__filename(env, filename);
    const char *_filename = utf__filename.get();
    LOGD("monerujo.cpp: Exporting key images");
    return wallet->exportKeyImages(_filename, all);

    // return env->NewStringUTF(outputs.c_str());
}


static std::mutex importKeyImagesMutex;

//virtual bool importKeyImages(const std::string &filename) = 0;
JNIEXPORT jstring JNICALL
Java_io_anonero_model_Wallet_importKeyImages(JNIEnv *env, jobject instance, jstring filename) {
    std::unique_lock<std::mutex> importLock(importKeyImagesMutex, std::try_to_lock);
    if (!importLock.owns_lock()) {
        LOGW("importKeyImages ignored because another import is already in progress");
        return env->NewStringUTF("A key-image import is already in progress.");
    }
    ScopedUtfChars utf__filename(env, filename);
    const char *_filename = utf__filename.get();
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    LOGD("monerujo.cpp: importKeyImages: start");
    bool success = wallet->importKeyImages(_filename);
    if (success) {
        LOGD("monerujo.cpp: importKeyImages: success!");
        return env->NewStringUTF("Imported");
    }
    LOGD("monerujo.cpp: importKeyImages: error");
    //LOGD(wallet->errorString().c_str());
    return env->NewStringUTF(wallet->errorString().c_str());
}


// virtual bool submitTransaction(const std::string &fileName) = 0;
JNIEXPORT jstring JNICALL
Java_io_anonero_model_Wallet_submitTransaction(JNIEnv *env, jobject instance, jstring filename) {
    ScopedUtfChars utf__filename(env, filename);
    const char *_filename = utf__filename.get();
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);

    bool success = wallet->submitTransaction(_filename);
    if (success) {
        return env->NewStringUTF("Transaction submitted!");
    }
    return env->NewStringUTF(wallet->errorString().c_str());
}

//virtual bool exportOutputs(const std::string &filename, bool all = false) = 0;
JNIEXPORT jboolean JNICALL
Java_io_anonero_model_Wallet_exportOutputs(JNIEnv *env, jobject instance, jstring filename,
                                           jboolean all) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    ScopedUtfChars utf__filename(env, filename);
    const char *_filename = utf__filename.get();
    return wallet->exportOutputs(_filename, all);
    // return env->NewStringUTF(outputs.c_str());
}

JNIEXPORT jboolean JNICALL
Java_io_anonero_model_Wallet_hasUnknownKeyImages(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return wallet->hasUnknownKeyImages();
}

JNIEXPORT jlong JNICALL
Java_io_anonero_model_Wallet_viewOnlyBalance(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return wallet->viewOnlyBalance(0); //NOTE: hardcoded account index
}

//virtual bool importOutputs(const std::string &filename) = 0;
JNIEXPORT jstring JNICALL
Java_io_anonero_model_Wallet_importOutputs(JNIEnv *env, jobject instance, jstring filename) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    ScopedUtfChars utf__filename(env, filename);
    const char *_filename = utf__filename.get();
    bool success = wallet->importOutputs(_filename);
    if (success) {
        return env->NewStringUTF("Imported");
    }
    return env->NewStringUTF(wallet->errorString().c_str());
}

JNIEXPORT jstring JNICALL
Java_io_anonero_model_Wallet_signAndExportJ(JNIEnv *env, jobject instance, jstring input_file,
                                            jstring output_file) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    ScopedUtfChars utf__input_file(env, input_file);
    const char *_input_file = utf__input_file.get();
    Monero::UnsignedTransaction *tx = wallet->loadUnsignedTx(_input_file);
    if (tx == nullptr) {
        const std::string error = wallet->errorString();
        return env->NewStringUTF(error.empty() ? "Unable to load unsigned transaction." : error.c_str());
    }
    ScopedUtfChars utf__output_file(env, output_file);
    if (output_file == nullptr) {
        jclass exceptionClass = env->FindClass("java/lang/IllegalArgumentException");
        if (exceptionClass != nullptr) {
            env->ThrowNew(exceptionClass, "Output file path must not be null.");
            env->DeleteLocalRef(exceptionClass);
        }
        return nullptr;
    }
    const char *_output_file = utf__output_file.get();


    bool success = tx->sign(_output_file);
    if (success) {
        return env->NewStringUTF("Signed");
    }
    return env->NewStringUTF(wallet->errorString().c_str());
}
JNIEXPORT jlong JNICALL
Java_io_anonero_model_Wallet_loadUnsignedTx(JNIEnv *env, jobject instance, jstring input_file) {
    LOGD("monerujo.cpp: loadUnsignedTx: start");
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    ScopedUtfChars utf__input_file(env, input_file);
    const char *_input_file = utf__input_file.get();
    Monero::UnsignedTransaction *tx = wallet->loadUnsignedTx(_input_file);
    LOGD("monerujo.cpp: loadUnsignedTx: end");
    if (tx == nullptr) {
        const std::string error = wallet->errorString();
        throwWalletCreationError(env, error.empty() ? "Unable to load unsigned transaction" : error.c_str());
        return 0;
    }
    return reinterpret_cast<jlong>(tx);
}

//virtual TransactionHistory * history() const = 0;
JNIEXPORT jlong JNICALL
Java_io_anonero_model_Wallet_getHistoryJ(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return reinterpret_cast<jlong>(wallet->history());
}

JNIEXPORT jlong JNICALL
Java_io_anonero_model_Wallet_getCoinsJ(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return reinterpret_cast<jlong>(wallet->coins());
}


jobject newCoinsInfo(JNIEnv *env, Monero::CoinsInfo *info) {
    jmethodID c = env->GetMethodID(class_CoinsInfo, "<init>",
                                   "(JZLjava/lang/String;JLjava/lang/String;Ljava/lang/String;ZZJ)V");
    jstring _key_image = env->NewStringUTF(info->keyImage().c_str());
    jstring _pub_key = env->NewStringUTF(info->pubKey().c_str());
    jstring _hash = env->NewStringUTF(info->hash().c_str());
    jobject result = env->NewObject(class_CoinsInfo, c,
                                    static_cast<jlong> (info->globalOutputIndex()),
                                    info->spent(),
                                    _key_image,
                                    static_cast<jlong> (info->amount()),
                                    _hash,
                                    _pub_key,
                                    info->frozen(),
                                    info->unlocked(),
                                    static_cast<jlong> (info->internalOutputIndex()));
    env->DeleteLocalRef(_key_image);
    env->DeleteLocalRef(_hash);
    env->DeleteLocalRef(_pub_key);
    return result;
}

jobject coins_cpp2java(JNIEnv *env, const std::vector<Monero::CoinsInfo *> &vector) {

    jmethodID java_util_ArrayList_ = env->GetMethodID(class_ArrayList, "<init>", "(I)V");
    jmethodID java_util_ArrayList_add = env->GetMethodID(class_ArrayList, "add",
                                                         "(Ljava/lang/Object;)Z");

    jobject arrayList = env->NewObject(class_ArrayList, java_util_ArrayList_,
                                       static_cast<jint> (vector.size()));
    for (Monero::CoinsInfo *s: vector) {
        jobject info = newCoinsInfo(env, s);
        env->CallBooleanMethod(arrayList, java_util_ArrayList_add, info);
        env->DeleteLocalRef(info);
    }
    return arrayList;
}

JNIEXPORT jint JNICALL
Java_io_anonero_model_Coins_getCount(JNIEnv *env, jobject instance) {
    Monero::Coins *coins = getHandle<Monero::Coins>(env, instance);
    return coins->count();
}

JNIEXPORT void JNICALL
Java_io_anonero_model_Coins_setFrozenJ(JNIEnv *env, jobject instance, jstring public_key) {
    Monero::Coins *coins = getHandle<Monero::Coins>(env, instance);
    if (coins == nullptr || public_key == nullptr) return;
    ScopedUtfChars utf_key(env, public_key);
    const char *key = utf_key.get();
    if (key == nullptr) return;
    coins->setFrozen(std::string(key));

}

JNIEXPORT void JNICALL
Java_io_anonero_model_Coins_thawJ(JNIEnv *env, jobject instance, jstring public_key) {
    Monero::Coins *coins = getHandle<Monero::Coins>(env, instance);
    if (coins == nullptr || public_key == nullptr) return;
    ScopedUtfChars utf_key(env, public_key);
    const char *key = utf_key.get();
    if (key == nullptr) return;
    coins->thaw(std::string(key));

}

JNIEXPORT jobject JNICALL
Java_io_anonero_model_Coins_refreshJ(JNIEnv *env, jobject instance) {
    Monero::Coins *coins = getHandle<Monero::Coins>(env, instance);
    coins->refresh();
    return coins_cpp2java(env, coins->getAll());
}


//virtual AddressBook * addressBook() const = 0;

JNIEXPORT jlong JNICALL
Java_io_anonero_model_Wallet_setListenerJ(JNIEnv *env, jobject instance,
                                          jobject javaListener) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    wallet->setListener(nullptr); // clear old listener
    // delete old listener
    MyWalletListener *oldListener = getHandle<MyWalletListener>(env, instance,
                                                                "listenerHandle");
    if (oldListener != nullptr) {
        oldListener->deleteGlobalJavaRef(env);
        delete oldListener;
    }
    if (javaListener == nullptr) {
        LOGD("null listener");
        return 0;
    } else {
        MyWalletListener *listener = new MyWalletListener(env, javaListener);
        wallet->setListener(listener);
        return reinterpret_cast<jlong>(listener);
    }
}

JNIEXPORT jint JNICALL
Java_io_anonero_model_Wallet_getDefaultMixin(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return wallet->defaultMixin();
}

JNIEXPORT void JNICALL
Java_io_anonero_model_Wallet_setDefaultMixin(JNIEnv *env, jobject instance, jint mixin) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return wallet->setDefaultMixin(mixin);
}

JNIEXPORT jboolean JNICALL
Java_io_anonero_model_Wallet_setUserNote(JNIEnv *env, jobject instance,
                                         jstring txid, jstring note) {

    ScopedUtfChars utf__txid(env, txid);
    const char *_txid = utf__txid.get();
    ScopedUtfChars utf__note(env, note);
    const char *_note = utf__note.get();

    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);

    bool success = wallet->setUserNote(_txid, _note);



    return static_cast<jboolean>(success);
}

JNIEXPORT jstring JNICALL
Java_io_anonero_model_Wallet_getUserNote(JNIEnv *env, jobject instance,
                                         jstring txid) {

    ScopedUtfChars utf__txid(env, txid);
    const char *_txid = utf__txid.get();

    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);

    std::string note = wallet->getUserNote(_txid);


    return env->NewStringUTF(note.c_str());
}

JNIEXPORT jstring JNICALL
Java_io_anonero_model_Wallet_getTxKey(JNIEnv *env, jobject instance,
                                      jstring txid) {

    ScopedUtfChars utf__txid(env, txid);
    const char *_txid = utf__txid.get();

    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);

    std::string txKey = wallet->getTxKey(_txid);


    return env->NewStringUTF(txKey.c_str());
}

//virtual void addSubaddressAccount(const std::string& label) = 0;
JNIEXPORT void JNICALL
Java_io_anonero_model_Wallet_addAccount(JNIEnv *env, jobject instance,
                                        jstring label) {

    ScopedUtfChars utf__label(env, label);
    const char *_label = utf__label.get();

    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    wallet->addSubaddressAccount(_label);


}

//virtual std::string getSubaddressLabel(uint32_t accountIndex, uint32_t addressIndex) const = 0;
JNIEXPORT jstring JNICALL
Java_io_anonero_model_Wallet_getSubaddressLabel(JNIEnv *env, jobject instance,
                                                jint accountIndex, jint addressIndex) {

    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);

    std::string label = wallet->getSubaddressLabel((uint32_t) accountIndex,
                                                   (uint32_t) addressIndex);

    return env->NewStringUTF(label.c_str());
}

//virtual void setSubaddressLabel(uint32_t accountIndex, uint32_t addressIndex, const std::string &label) = 0;
JNIEXPORT void JNICALL
Java_io_anonero_model_Wallet_setSubaddressLabel(JNIEnv *env, jobject instance,
                                                jint accountIndex, jint addressIndex,
                                                jstring label) {

    ScopedUtfChars utf__label(env, label);
    const char *_label = utf__label.get();

    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    wallet->setSubaddressLabel(accountIndex, addressIndex, _label);


}

// virtual size_t numSubaddressAccounts() const = 0;
JNIEXPORT jint JNICALL
Java_io_anonero_model_Wallet_getNumAccounts(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return static_cast<jint>(wallet->numSubaddressAccounts());
}

//virtual size_t numSubaddresses(uint32_t accountIndex) const = 0;
JNIEXPORT jint JNICALL
Java_io_anonero_model_Wallet_getNumSubaddresses(JNIEnv *env, jobject instance,
                                                jint accountIndex) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return static_cast<jint>(wallet->numSubaddresses(accountIndex));
}

//virtual void addSubaddress(uint32_t accountIndex, const std::string &label) = 0;
JNIEXPORT void JNICALL
Java_io_anonero_model_Wallet_addSubaddress(JNIEnv *env, jobject instance,
                                           jint accountIndex,
                                           jstring label) {

    ScopedUtfChars utf__label(env, label);
    const char *_label = utf__label.get();
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    wallet->addSubaddress(accountIndex, _label);

}

/*JNIEXPORT jstring JNICALL
Java_io_anonero_model_Wallet_getLastSubaddress(JNIEnv *env, jobject instance,
                                                         jint accountIndex) {

    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    size_t num = wallet->numSubaddresses(accountIndex);
    //wallet->subaddress()->getAll()[num]->getAddress().c_str()
    Monero::Subaddress *s = wallet->subaddress();
    s->refresh(accountIndex);
    std::vector<Monero::SubaddressRow *> v = s->getAll();
    return env->NewStringUTF(v[num - 1]->getAddress().c_str());
}
*/
//virtual std::string signMessage(const std::string &message) = 0;
//virtual bool verifySignedMessage(const std::string &message, const std::string &addres, const std::string &signature) const = 0;

//virtual bool parse_uri(const std::string &uri, std::string &address, std::string &payment_id, uint64_t &tvAmount, std::string &tx_description, std::string &recipient_name, std::vector<std::string> &unknown_parameters, std::string &error) = 0;
//virtual bool rescanSpent() = 0;


// TransactionHistory
JNIEXPORT jint JNICALL
Java_io_anonero_model_TransactionHistory_getCount(JNIEnv *env, jobject instance) {
    Monero::TransactionHistory *history = getHandle<Monero::TransactionHistory>(env,
                                                                                instance);
    return history->count();
}

jobject newTransferInstance(JNIEnv *env, uint64_t amount, const std::string &address) {
    jmethodID c = env->GetMethodID(class_Transfer, "<init>",
                                   "(JLjava/lang/String;)V");
    jstring _address = env->NewStringUTF(address.c_str());
    jobject transfer = env->NewObject(class_Transfer, c, static_cast<jlong> (amount), _address);
    env->DeleteLocalRef(_address);
    return transfer;
}

jobject newTransferList(JNIEnv *env, Monero::TransactionInfo *info) {
    const std::vector<Monero::TransactionInfo::Transfer> &transfers = info->transfers();
    if (transfers.empty()) { // don't create empty Lists
        return nullptr;
    }
    // make new ArrayList
    jmethodID java_util_ArrayList_ = env->GetMethodID(class_ArrayList, "<init>", "(I)V");
    jmethodID java_util_ArrayList_add = env->GetMethodID(class_ArrayList, "add",
                                                         "(Ljava/lang/Object;)Z");
    jobject result = env->NewObject(class_ArrayList, java_util_ArrayList_,
                                    static_cast<jint> (transfers.size()));
    // create Transfer objects and stick them in the List
    for (const Monero::TransactionInfo::Transfer &s: transfers) {
        jobject element = newTransferInstance(env, s.amount, s.address);
        env->CallBooleanMethod(result, java_util_ArrayList_add, element);
        env->DeleteLocalRef(element);
    }
    return result;
}

jobject newTransactionInfo(JNIEnv *env, Monero::TransactionInfo *info) {
    jmethodID c = env->GetMethodID(class_TransactionInfo, "<init>",
                                   "(IZZJJJLjava/lang/String;JLjava/lang/String;IIJLjava/lang/String;Ljava/util/List;)V");
    jobject transfers = newTransferList(env, info);
    jstring _hash = env->NewStringUTF(info->hash().c_str());
    jstring _paymentId = env->NewStringUTF(info->paymentId().c_str());
    jstring _label = env->NewStringUTF(info->label().c_str());
    uint32_t subaddrIndex = 0;
    if (info->direction() == Monero::TransactionInfo::Direction_In)
        subaddrIndex = *(info->subaddrIndex().begin());
    jobject result = env->NewObject(class_TransactionInfo, c,
                                    info->direction(),
                                    info->isPending(),
                                    info->isFailed(),
                                    static_cast<jlong> (info->amount()),
                                    static_cast<jlong> (info->fee()),
                                    static_cast<jlong> (info->blockHeight()),
                                    _hash,
                                    static_cast<jlong> (info->timestamp()),
                                    _paymentId,
                                    static_cast<jint> (info->subaddrAccount()),
                                    static_cast<jint> (subaddrIndex),
                                    static_cast<jlong> (info->confirmations()),
                                    _label,
                                    transfers);
    env->DeleteLocalRef(transfers);
    env->DeleteLocalRef(_hash);
    env->DeleteLocalRef(_paymentId);
    return result;
}

#include <stdio.h>
#include <stdlib.h>

jobject cpp2java(JNIEnv *env, const std::vector<Monero::TransactionInfo *> &vector) {

    jmethodID java_util_ArrayList_ = env->GetMethodID(class_ArrayList, "<init>", "(I)V");
    jmethodID java_util_ArrayList_add = env->GetMethodID(class_ArrayList, "add",
                                                         "(Ljava/lang/Object;)Z");

    jobject arrayList = env->NewObject(class_ArrayList, java_util_ArrayList_,
                                       static_cast<jint> (vector.size()));
    for (Monero::TransactionInfo *s: vector) {
        jobject info = newTransactionInfo(env, s);
        env->CallBooleanMethod(arrayList, java_util_ArrayList_add, info);
        env->DeleteLocalRef(info);
    }
    return arrayList;
}

JNIEXPORT jobject JNICALL
Java_io_anonero_model_TransactionHistory_refreshJ(JNIEnv *env, jobject instance) {
    Monero::TransactionHistory *history = getHandle<Monero::TransactionHistory>(env,
                                                                                instance);
    history->refresh();
    return cpp2java(env, history->getAll());
}

// TransactionInfo is implemented in Java - no need here

JNIEXPORT jint JNICALL
Java_io_anonero_model_PendingTransaction_getStatusJ(JNIEnv *env, jobject instance) {
    Monero::PendingTransaction *tx = getHandle<Monero::PendingTransaction>(env, instance);
    return tx->status();
}

JNIEXPORT jstring JNICALL
Java_io_anonero_model_PendingTransaction_getErrorString(JNIEnv *env, jobject instance) {
    Monero::PendingTransaction *tx = getHandle<Monero::PendingTransaction>(env, instance);
    return env->NewStringUTF(tx->errorString().c_str());
}

// commit transaction or save to file if filename is provided.
JNIEXPORT jboolean JNICALL
Java_io_anonero_model_PendingTransaction_commit(JNIEnv *env, jobject instance,
                                                jstring filename, jboolean overwrite) {

    ScopedUtfChars utf__filename(env, filename);
    const char *_filename = utf__filename.get();

    Monero::PendingTransaction *tx = getHandle<Monero::PendingTransaction>(env, instance);
    bool success = tx->commit(_filename, overwrite);


    return static_cast<jboolean>(success);
}


JNIEXPORT jlong JNICALL
Java_io_anonero_model_PendingTransaction_getAmount(JNIEnv *env, jobject instance) {
    Monero::PendingTransaction *tx = getHandle<Monero::PendingTransaction>(env, instance);
    return tx->amount();
}

JNIEXPORT jlong JNICALL
Java_io_anonero_model_PendingTransaction_getDust(JNIEnv *env, jobject instance) {
    Monero::PendingTransaction *tx = getHandle<Monero::PendingTransaction>(env, instance);
    return tx->dust();
}

JNIEXPORT jlong JNICALL
Java_io_anonero_model_PendingTransaction_getFee(JNIEnv *env, jobject instance) {
    Monero::PendingTransaction *tx = getHandle<Monero::PendingTransaction>(env, instance);
    return tx->fee();
}


//Unsigned tx java implementation


JNIEXPORT jint JNICALL
Java_io_anonero_model_UnsignedTransaction_getStatusJ(JNIEnv *env, jobject instance) {
    Monero::UnsignedTransaction *tx = getHandle<Monero::UnsignedTransaction>(env, instance);
    return tx->status();
}

JNIEXPORT jstring JNICALL
Java_io_anonero_model_UnsignedTransaction_getErrorString(JNIEnv *env, jobject instance) {
    Monero::UnsignedTransaction *tx = getHandle<Monero::UnsignedTransaction>(env, instance);
    return env->NewStringUTF(tx->errorString().c_str());
}

JNIEXPORT jstring JNICALL
Java_io_anonero_model_UnsignedTransaction_getAddress(JNIEnv *env, jobject instance) {
    Monero::UnsignedTransaction *tx = getHandle<Monero::UnsignedTransaction>(env, instance);

    std::vector<std::string> arr = tx->recipientAddress();
    if (0 > arr.size() - 1)
        return env->NewStringUTF("");

    return env->NewStringUTF(arr[0].c_str());
}


JNIEXPORT jlong JNICALL
Java_io_anonero_model_UnsignedTransaction_getAmount(JNIEnv *env, jobject instance) {
    Monero::UnsignedTransaction *tx = getHandle<Monero::UnsignedTransaction>(env, instance);
    std::vector<uint64_t> arr = tx->amount();
    if (0 > arr.size() - 1)
        return 0;
    return static_cast<jlong>(arr[0]);
}


JNIEXPORT jlong JNICALL
Java_io_anonero_model_UnsignedTransaction_getFee(JNIEnv *env, jobject instance) {
    Monero::UnsignedTransaction *tx = getHandle<Monero::UnsignedTransaction>(env, instance);
    std::vector<uint64_t> arr = tx->fee();
    if (0 > arr.size() - 1)
        return 0;
    return static_cast<jlong>(arr[0]);
}

// TODO this returns a vector of strings - deal with this later - for now return first one
JNIEXPORT jstring JNICALL
Java_io_anonero_model_UnsignedTransaction_getFirstTxIdJ(JNIEnv *env, jobject instance) {
    Monero::UnsignedTransaction *tx = getHandle<Monero::UnsignedTransaction>(env, instance);
    std::vector<std::string> txids = tx->paymentId();
    if (!txids.empty())
        return env->NewStringUTF(txids.front().c_str());
    else
        return nullptr;
}

JNIEXPORT jlong JNICALL
Java_io_anonero_model_UnsignedTransaction_getTxCount(JNIEnv *env, jobject instance) {
    Monero::UnsignedTransaction *tx = getHandle<Monero::UnsignedTransaction>(env, instance);
    return tx->txCount();
}




// TODO this returns a vector of strings - deal with this later - for now return first one
JNIEXPORT jstring JNICALL
Java_io_anonero_model_PendingTransaction_getFirstTxIdJ(JNIEnv *env, jobject instance) {
    Monero::PendingTransaction *tx = getHandle<Monero::PendingTransaction>(env, instance);
    std::vector<std::string> txids = tx->txid();
    if (!txids.empty())
        return env->NewStringUTF(txids.front().c_str());
    else
        return nullptr;
}

JNIEXPORT jlong JNICALL
Java_io_anonero_model_PendingTransaction_getTxCount(JNIEnv *env, jobject instance) {
    Monero::PendingTransaction *tx = getHandle<Monero::PendingTransaction>(env, instance);
    return tx->txCount();
}


// these are all in Monero::Wallet - which I find wrong, so they are here!
//static void init(const char *argv0, const char *default_log_base_name);
//static void debug(const std::string &category, const std::string &str);
//static void info(const std::string &category, const std::string &str);
//static void warning(const std::string &category, const std::string &str);
//static void error(const std::string &category, const std::string &str);
JNIEXPORT void JNICALL
Java_io_anonero_model_WalletManager_initLogger(JNIEnv *env, jclass clazz,
                                               jstring argv0,
                                               jstring default_log_base_name) {

    ScopedUtfChars utf__argv0(env, argv0);
    const char *_argv0 = utf__argv0.get();
    ScopedUtfChars utf__default_log_base_name(env, default_log_base_name);
    const char *_default_log_base_name = utf__default_log_base_name.get();

    Monero::Wallet::init(_argv0, _default_log_base_name);



}

JNIEXPORT void JNICALL
Java_io_anonero_model_WalletManager_logDebug(JNIEnv *env, jclass clazz,
                                             jstring category, jstring message) {

    ScopedUtfChars utf__category(env, category);
    const char *_category = utf__category.get();
    ScopedUtfChars utf__message(env, message);
    const char *_message = utf__message.get();

    Monero::Wallet::debug(_category, _message);



}

JNIEXPORT void JNICALL
Java_io_anonero_model_WalletManager_logInfo(JNIEnv *env, jclass clazz,
                                            jstring category, jstring message) {

    ScopedUtfChars utf__category(env, category);
    const char *_category = utf__category.get();
    ScopedUtfChars utf__message(env, message);
    const char *_message = utf__message.get();

    Monero::Wallet::info(_category, _message);



}

JNIEXPORT void JNICALL
Java_io_anonero_model_WalletManager_logWarning(JNIEnv *env, jclass clazz,
                                               jstring category, jstring message) {

    ScopedUtfChars utf__category(env, category);
    const char *_category = utf__category.get();
    ScopedUtfChars utf__message(env, message);
    const char *_message = utf__message.get();

    Monero::Wallet::warning(_category, _message);



}

JNIEXPORT void JNICALL
Java_io_anonero_model_WalletManager_logError(JNIEnv *env, jclass clazz,
                                             jstring category, jstring message) {

    ScopedUtfChars utf__category(env, category);
    const char *_category = utf__category.get();
    ScopedUtfChars utf__message(env, message);
    const char *_message = utf__message.get();

    Monero::Wallet::error(_category, _message);



}

JNIEXPORT void JNICALL
Java_io_anonero_model_WalletManager_setLogLevel(JNIEnv *env, jclass clazz,
                                                jint level) {
    Monero::WalletManagerFactory::setLogLevel(level);
}

JNIEXPORT jstring JNICALL
Java_io_anonero_model_WalletManager_moneroVersion(JNIEnv *env, jclass clazz) {
    return env->NewStringUTF(MONERO_VERSION);
}

//
// Ledger Stuff
//

/**
 * @brief LedgerExchange - exchange data with Ledger Device
 * @param command        - buffer for data to send
 * @param cmd_len        - length of send to send
 * @param response       - buffer for received data
 * @param max_resp_len   - size of receive buffer
 *
 * @return length of received data in response or -1 if error
 */
int LedgerExchange(
        unsigned char *command,
        unsigned int cmd_len,
        unsigned char *response,
        unsigned int max_resp_len) {
    LOGD("LedgerExchange");
    JNIEnv *jenv;
    int envStat = attachJVM(&jenv);
    if (envStat == JNI_ERR) return -1;

    jmethodID exchangeMethod = jenv->GetStaticMethodID(class_Ledger, "Exchange", "([B)[B");

    jsize sendLen = static_cast<jsize>(cmd_len);
    jbyteArray dataSend = jenv->NewByteArray(sendLen);
    jenv->SetByteArrayRegion(dataSend, 0, sendLen, (jbyte *) command);
    jbyteArray dataRecv = (jbyteArray) jenv->CallStaticObjectMethod(class_Ledger, exchangeMethod,
                                                                    dataSend);
    jenv->DeleteLocalRef(dataSend);
    if (dataRecv == nullptr) {
        detachJVM(jenv, envStat);
        LOGD("LedgerExchange SCARD_E_NO_READERS_AVAILABLE");
        return -1;
    }
    jsize len = jenv->GetArrayLength(dataRecv);
    LOGD("LedgerExchange SCARD_S_SUCCESS %u/%d", cmd_len, len);
    if (len <= max_resp_len) {
        jenv->GetByteArrayRegion(dataRecv, 0, len, (jbyte *) response);
        jenv->DeleteLocalRef(dataRecv);
        detachJVM(jenv, envStat);
        return static_cast<int>(len);;
    } else {
        jenv->DeleteLocalRef(dataRecv);
        detachJVM(jenv, envStat);
        LOGE("LedgerExchange SCARD_E_INSUFFICIENT_BUFFER");
        return -1;
    }
}


// from monero-core crypto/hash-ops.h - avoid #including monero code here

/**
 * @brief LedgerFind - find Ledger Device and return it's name
 * @param buffer - buffer for name of found device
 * @param len    - length of buffer
 * @return  0 - success
 *         -1 - no device connected / found
 *         -2 - JVM not found
 */
int LedgerFind(char *buffer, size_t len) {
    LOGD("LedgerName");
    JNIEnv *jenv;
    int envStat = attachJVM(&jenv);
    if (envStat == JNI_ERR) return -2;

    jmethodID nameMethod = jenv->GetStaticMethodID(class_Ledger, "Name", "()Ljava/lang/String;");
    jstring name = (jstring) jenv->CallStaticObjectMethod(class_Ledger, nameMethod);

    int ret;
    if (name != nullptr) {
        ScopedUtfChars utf__name(jenv, name);
    const char *_name = utf__name.get();
        strncpy(buffer, _name, len);

        buffer[len - 1] = 0; // terminate in case _name is bigger
        ret = 0;
        LOGD("LedgerName is %s", buffer);
    } else {
        buffer[0] = 0;
        ret = -1;
    }

    detachJVM(jenv, envStat);
    return ret;
}

#ifdef __cplusplus
}
#endif
