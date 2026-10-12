package de.maxhenkel.voicechat.voice.client;

import com.mojang.blaze3d.platform.InputConstants;
import de.maxhenkel.voicechat.intercompatibility.ClientCompatibilityManager;

public class PTTKeyHandler {

    private boolean pttKeyDown;
    private boolean whisperKeyDown;
    private boolean megaphoneKeyDown;
    private boolean announceKeyDown;

    public PTTKeyHandler() {
        ClientCompatibilityManager.INSTANCE.onKeyboardEvent(this::onKeyboardEvent);
        ClientCompatibilityManager.INSTANCE.onMouseEvent(this::onMouseEvent);
    }

    public void onKeyboardEvent(long window, int key, int scancode) {
        InputConstants.Key pttKey = ClientCompatibilityManager.INSTANCE.getBoundKeyOf(KeyEvents.KEY_PTT);
        if (pttKey.getValue() != -1 && !pttKey.getType().equals(InputConstants.Type.MOUSE)) {
            pttKeyDown = InputConstants.isKeyDown(window, pttKey.getValue());
        }

        InputConstants.Key whisperKey = ClientCompatibilityManager.INSTANCE.getBoundKeyOf(KeyEvents.KEY_WHISPER);
        if (whisperKey.getValue() != -1 && !whisperKey.getType().equals(InputConstants.Type.MOUSE)) {
            whisperKeyDown = InputConstants.isKeyDown(window, whisperKey.getValue());
        }

        InputConstants.Key megaphoneKey = ClientCompatibilityManager.INSTANCE.getBoundKeyOf(KeyEvents.KEY_MEGAPHONE);
        if (megaphoneKey.getValue() != -1 && !megaphoneKey.getType().equals(InputConstants.Type.MOUSE)) {
            megaphoneKeyDown = InputConstants.isKeyDown(window, megaphoneKey.getValue());
        }

        InputConstants.Key announceKey = ClientCompatibilityManager.INSTANCE.getBoundKeyOf(KeyEvents.KEY_ANNOUNCE);
        if (announceKey.getValue() != -1 && !announceKey.getType().equals(InputConstants.Type.MOUSE)) {
            announceKeyDown = InputConstants.isKeyDown(window, announceKey.getValue());
        }
    }

    public void onMouseEvent(long window, int button, int action, int mods) {
        InputConstants.Key pttKey = ClientCompatibilityManager.INSTANCE.getBoundKeyOf(KeyEvents.KEY_PTT);
        if (pttKey.getValue() != -1 && pttKey.getType().equals(InputConstants.Type.MOUSE) && pttKey.getValue() == button) {
            pttKeyDown = action != 0;
        }

        InputConstants.Key whisperKey = ClientCompatibilityManager.INSTANCE.getBoundKeyOf(KeyEvents.KEY_WHISPER);
        if (whisperKey.getValue() != -1 && whisperKey.getType().equals(InputConstants.Type.MOUSE) && whisperKey.getValue() == button) {
            whisperKeyDown = action != 0;
        }

        InputConstants.Key megaphoneKey = ClientCompatibilityManager.INSTANCE.getBoundKeyOf(KeyEvents.KEY_MEGAPHONE);
        if (megaphoneKey.getValue() != -1 && megaphoneKey.getType().equals(InputConstants.Type.MOUSE) && megaphoneKey.getValue() == button) {
            megaphoneKeyDown = action != 0;
        }

        InputConstants.Key announceKey = ClientCompatibilityManager.INSTANCE.getBoundKeyOf(KeyEvents.KEY_ANNOUNCE);
        if (announceKey.getValue() != -1 && announceKey.getType().equals(InputConstants.Type.MOUSE) && announceKey.getValue() == button) {
            announceKeyDown = action != 0;
        }
    }

    public boolean isPTTDown() {
        return pttKeyDown;
    }

    public boolean isWhisperDown() {
        return whisperKeyDown;
    }

    public boolean isMegaphoneDown() {
        return megaphoneKeyDown;
    }

    public boolean isAnnounceDown() {
        return announceKeyDown;
    }

    public boolean isAnyDown() {
        return pttKeyDown || whisperKeyDown || megaphoneKeyDown || announceKeyDown;
    }

}
