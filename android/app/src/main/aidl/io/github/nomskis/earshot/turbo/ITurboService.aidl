package io.github.nomskis.earshot.turbo;

// Runs inside a Shizuku user service, i.e. with the adb shell identity.
interface ITurboService {
    // Shizuku calls this transaction to stop the service.
    void destroy() = 16777114;

    // Output of `dumpsys <service>` for an allow-listed service.
    String dump(String service) = 1;

    // AudioManager.setBluetoothVariableLatencyEnabled; returns the resulting state.
    boolean setVariableLatency(boolean enabled) = 2;

    // Current and selectable codecs of the active A2DP device, as "type:sampleRate:bits:specific1|sel1,sel2".
    String codecStatus() = 3;

    // BluetoothA2dp.setCodecConfigPreference for the active device.
    boolean setCodec(int codecType, long codecSpecific1) = 4;

    // BluetoothA2dp.getDynamicBufferSupport (0 = none).
    int dynamicBufferSupport() = 5;

    // Smallest buffer the stack allows for a codec, in ms, or -1.
    int minBufferMillis(int codecType) = 6;

    // BluetoothA2dp.setBufferLengthMillis.
    boolean setBufferMillis(int codecType, int millis) = 7;

    // The stack's default buffer for a codec, in ms, or -1.
    int defaultBufferMillis(int codecType) = 8;
}
