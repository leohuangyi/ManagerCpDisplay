LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := xcertplay_i2c
LOCAL_SRC_FILES := linux_i2c_jni.c
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := local_hotspot_radio
LOCAL_SRC_FILES := local_hotspot_radio.c
LOCAL_CFLAGS := -Wall -Wextra -Werror
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := speex_echo
LOCAL_SRC_FILES := speex_echo_jni.c \
    speexdsp/mdf.c speexdsp/preprocess.c speexdsp/fftwrap.c \
    speexdsp/kiss_fft.c speexdsp/kiss_fftr.c speexdsp/filterbank.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/speexdsp $(LOCAL_PATH)/speexdsp/include
LOCAL_CFLAGS := -O2 -DFLOATING_POINT -DUSE_KISS_FFT -DEXPORT= -Wno-unused-parameter
LOCAL_LDLIBS := -lm
include $(BUILD_SHARED_LIBRARY)

# Apple AccessorySDK ChaCha20-Poly1305 algorithm code (chacha/, verbatim copy with its license
# notice retained) with a DiPlay JNI bridge. -DEXCLUDE_UNIT_TESTS drops the self-test sections.
include $(CLEAR_VARS)
LOCAL_MODULE := diplay_chacha
LOCAL_SRC_FILES := chacha_jni.c chacha/ChaCha20Poly1305.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/chacha
LOCAL_CFLAGS := -O2 -DEXCLUDE_UNIT_TESTS -fno-strict-aliasing -Wall -Wextra -Wno-unused-parameter
include $(BUILD_SHARED_LIBRARY)
