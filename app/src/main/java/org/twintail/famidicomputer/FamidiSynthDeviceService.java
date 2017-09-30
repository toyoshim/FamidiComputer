package org.twintail.famidicomputer;

import android.media.midi.MidiDeviceService;
import android.media.midi.MidiDeviceStatus;
import android.media.midi.MidiReceiver;
import android.util.Log;

public final class FamidiSynthDeviceService extends MidiDeviceService {
    private static final String TAG = "FamidiSynthDeviceService";
    private FamidiReceiver famidiReceiver = new FamidiReceiver();

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public void onDestroy() {
        famidiReceiver.stop();
        super.onDestroy();
    }

    @Override
    public MidiReceiver[] onGetInputPortReceivers() {
        return new MidiReceiver[] { famidiReceiver };
    }

    @Override
    public void onDeviceStatusChanged(MidiDeviceStatus status) {
        if (status.isInputPortOpen(0))
            famidiReceiver.start();
        else
            famidiReceiver.stop();
    }
}
