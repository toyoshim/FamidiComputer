package org.twintail.famidicomputer;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.AudioTrack.OnPlaybackPositionUpdateListener;
import android.media.midi.MidiReceiver;
import android.util.Log;

final class FamidiReceiver extends MidiReceiver implements OnPlaybackPositionUpdateListener {
    private static final String TAG = "FamidiReceiver";
    private int sampleRate = 44100;
    private int runningStatus = 0;
    private AudioTrack track = null;
    private short[] buffer = null;
    private FamidiChannel[] channels = new FamidiChannel[16];

    public void start() {
        if (track == null) {
            sampleRate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC);

            int bufferSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT) * 2;
            buffer = new short[bufferSize / 2];

            channels[0] = new FamidiChannel(sampleRate);

            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(bufferSize)
                    .build();
            track.setPlaybackPositionUpdateListener(this);
            track.setPositionNotificationPeriod(buffer.length);
        }

        track.play();
        track.write(buffer, 0, buffer.length);
        track.write(buffer, 0, buffer.length);
    }

    public void stop() {
        track.stop();
    }

    public void onSend(byte[] msg, int offset, int count, long timestamp) {
        int last = offset + count - 1;
        for (int i = offset; i <= last; ++i) {
            int status = msg[i] & 0xff;
            if (status < 0x80)
                status = runningStatus;
            if (status < 0x80) {
                Log.w(TAG, "unexpected MIDI sequence: $" + Integer.toHexString(status));
                return;
            }
            runningStatus = status;
            int channel = status & 0x0f;
            switch (status & 0xf0) {
                case 0x80:
                    if (!isValid(i, 3, last, msg))
                        return;
                    onNoteOff(channel, msg[i + 1] & 0x7f, msg[i + 2] & 0x7f);
                    i += 2;
                    break;
                case 0x90:
                    if (!isValid(i, 3, last, msg))
                        return;
                    onNoteOn(channel, msg[i + 1] & 0x7f, msg[i + 2] & 0x7f);
                    i += 2;
                    break;
                case 0xa0:
                    if (!isValid(i, 3, last, msg))
                        return;
                    onPolyphonicKeyPressure(channel, msg[i + 1] & 0x7f, msg[i + 2] & 0x7f);
                    i += 2;
                    break;
                case 0xb0:
                    if (!isValid(i, 3, last, msg))
                        return;
                    onControlChange(channel, msg[i + 1] & 0x7f, msg[i + 2] & 0x7f);
                    i += 2;
                    break;
                case 0xc0:
                    if (!isValid(i, 2, last, msg))
                        return;
                    onProgramChange(channel, msg[i + 1] & 0x7f);
                    i += 1;
                    break;
                case 0xd0:
                    if (!isValid(i, 2, last, msg))
                        return;
                    onChannelPressure(channel, msg[i + 1] & 0x7f);
                    i += 1;
                    break;
                case 0xe0:
                    if (!isValid(i, 3, last, msg))
                        return;
                    // 14-bit unsigned value, 0x2000 is the center value.
                    onPitchBendChange(channel, ((msg[i + 2] & 0x7f) << 7) | (msg[i + 1] & 0x7f));
                    i += 2;
                    break;
                case 0xf0:
                    switch (status) {
                        case 0xf0:  // SysEx
                            for (; i <= last; ++i) {
                                int value = msg[i] & 0xff;
                                if (value == 0xf7)
                                    break;
                            }
                            int value = msg[i] & 0xff;
                            if (value == 0xf7)
                                break;
                            Log.w(TAG, "incomplete SysEx message");
                            return;
                        case 0xf1:  // MIDI Time Code Quarter Frame
                            if (!isValid(i, 2, last, msg))
                                return;
                            i += 1;
                            break;
                        case 0xf2:  // Song Position Pointer
                            if (!isValid(i, 3, last, msg))
                                return;
                            i += 2;
                            break;
                        case 0xf3:  // Song Select
                            if (!isValid(i, 2, last, msg))
                                return;
                            i += 1;
                            break;
                        case 0xf4:  // Undefined
                        case 0xf5:  // Undefined
                        case 0xf9:  // Undefined
                        case 0xfd:  // Undefined
                            Log.w(TAG, "undefined MIDI message");
                            return;
                        case 0xf6:  // Tune Request
                        case 0xf7:  // End of SysEx
                        case 0xf8:  // Timing Clock
                        case 0xfa:  // Start
                        case 0xfb:  // Continue
                        case 0xfc:  // Stop
                        case 0xfe:  // Active Sensing
                        case 0xff:  // Reset
                            break;
                    }
                    break;
            }
        }
    }

    // OnPlaybackPositionUpdateListener
    public void onMarkerReached(final AudioTrack audioTrack) {}

    // OnPlaybackPositionUpdateListener
    public void onPeriodicNotification(final AudioTrack audioTrack) {
        for (int i = 0; i < buffer.length; ++i) {
            double value = 0.0;
            for (int ch = 0; ch < channels.length; ++ch) {
                if (channels[ch] != null)
                    value += channels[ch].process();
            }
            buffer[i] = (value > 1.0) ? (short)0x7fff
                                      : (value < -1.0) ? (short)0x8000 : (short)(value * 0x7fff);
        }
        track.write(buffer, 0, buffer.length);
    }

    private boolean isValid(int offset, int size, int last, byte[] msg) {
        if (offset + size - 1 > last) {
            Log.w(TAG, "incomplete MIDI message");
            return false;
        }
        for (int i = 1; i < size; ++i) {
            if (msg[offset + i] < 0) {
                Log.w(TAG, "unexpected MIDI status byte");
                return false;
            }
        }
        return true;
    }

    private void onNoteOff(int channel, int note, int velocity) {
        if (channels[channel] == null)
            return;
        channels[channel].noteOff(note);
    }

    private void onNoteOn(int channel, int note, int velocity) {
        if (channels[channel] == null)
            return;
        if (velocity == 0)
            channels[channel].noteOff(note);
        else
            channels[channel].noteOn(note, velocity);
    }

    private void onPolyphonicKeyPressure(int channel, int note, int pressure) {
    }

    private void onControlChange(int channel, int control, int value) {
    }

    private void onProgramChange(int channel, int program) {
        if (channels[channel] == null)
            return;
        channels[channel].programChange(program);
    }

    private void onChannelPressure(int channel, int pressure) {
    }

    private void onPitchBendChange(int channel, int bend) {
    }
}
