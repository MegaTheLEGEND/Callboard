package dev.callboard;

import android.os.ParcelFileDescriptor;

/** Runs as the shell user (uid 2000) via Shizuku. Deliberately narrow. */
interface IShell {
    /** Shizuku's UserService teardown hook; the code is fixed by Shizuku. */
    void destroy() = 16777114;

    int getUid() = 1;

    /**
     * Plays a clip into the active cellular call's uplink. [pcm] is a pipe read end carrying mono
     * PCM16LE at [rate] (8000 or 16000) until EOF. Replaces any clip still playing. Returns immediately.
     */
    void play(in ParcelFileDescriptor pcm, int rate) = 2;

    /** Stops the current clip / stream (the tap closes itself shortly after). */
    void stop() = 3;

    /** One-line state of the injector plus audio capability summary. */
    String status() = 4;

    /** Audio path for the NEXT tap: 0 auto (redirection, then direct), 1 redirection only, 2 direct only. */
    void setMethod(int method) = 5;

    /**
     * Live uplink: [pcm] is a pipe read end carrying a continuous mono PCM16LE stream at [rate]
     * (8000 or 16000). The helper paces it into the call in 20 ms frames until [endStream] / EOF.
     */
    void playStream(in ParcelFileDescriptor pcm, int rate) = 6;

    /** Ends the live uplink stream (the tap closes itself shortly after). */
    void endStream() = 7;

    /**
     * Starts microphone capture inside the helper. Writes 20 ms frames of mono PCM16LE at [rate]
     * (8000, 16000 or 48000) into the pipe write end [sink]. [source] is a MediaRecorder.AudioSource id.
     */
    void startMic(in ParcelFileDescriptor sink, int rate, int source) = 8;

    void stopMic() = 9;

    /**
     * While true, the helper clears the platform microphone-mute flag during a live uplink, so muting
     * the call in the dialer does not silence the injected audio. The mute is put back when the tap closes.
     */
    void setKeepUnmuted(boolean on) = 10;

    /**
     * Switches Wi-Fi on or off from the shell uid (`svc wifi enable|disable`). Returns true if the command succeeded.
     */
    boolean setWifiEnabled(boolean on) = 11;

    /**
     * Mutes / un-mutes the call microphone: the same platform flag the dialer's mute button sets. Returns true if the
     * flag matches [on] afterwards.
     */
    boolean setMicMuted(boolean on) = 12;

    /**
     * Tries to move the call between speakerphone ([on]) and the earpiece. Android lets only the phone app own the
     * call route on some versions, so this can fail; returns true only if the route really matches [on] afterwards.
     */
    boolean setSpeakerphone(boolean on) = 13;

    /** Hangs up the active call (Telecom, falling back to the hardware End key). Returns true if a hang-up was issued. */
    boolean endCall() = 14;
}
