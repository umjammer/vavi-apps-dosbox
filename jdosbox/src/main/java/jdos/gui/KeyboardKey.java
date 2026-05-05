package jdos.gui;

import java.awt.event.KeyEvent;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.HashMap;
import java.util.Map;

import jdos.sdl.JavaMapper;
import jdos.sdl.JavaMapper.DefaultKey;

import static java.awt.event.KeyEvent.*;


public class KeyboardKey {

    private static final Logger logger = System.getLogger(KeyboardKey.class.getName());

    static public boolean isLeft(Object key) {
        return ((KeyEvent) key).getKeyLocation() == KeyEvent.KEY_LOCATION_LEFT;
    }

    static public boolean isRight(Object key) {
        return ((KeyEvent) key).getKeyLocation() == KeyEvent.KEY_LOCATION_RIGHT;
    }

    static public boolean isNumPad(Object key) {
        return ((KeyEvent) key).getKeyLocation() == KeyEvent.KEY_LOCATION_NUMPAD;
    }

    static public boolean isPressed(Object key) {
        return ((KeyEvent) key).getID() == KeyEvent.KEY_PRESSED;
    }

    static public boolean isReleased(Object key) {
        return ((KeyEvent) key).getID() == KeyEvent.KEY_RELEASED;
    }

    static public int getKeyCode(Object key) {
        return ((KeyEvent) key).getKeyCode();
    }

    static public void createDefaultBinds() {
        JavaMapper.createStringBind("mod_1 \"key " + KeyEvent.VK_CONTROL + " right\"");
        JavaMapper.createStringBind("mod_2 \"key " + KeyEvent.VK_ALT + " right\"");
    }

    static public int translateMapKey(int key) {
        switch (key) {
            case Mapper.MapKeys.MK_f1:
            case Mapper.MapKeys.MK_f2:
            case Mapper.MapKeys.MK_f3:
            case Mapper.MapKeys.MK_f4:
            case Mapper.MapKeys.MK_f5:
            case Mapper.MapKeys.MK_f6:
            case Mapper.MapKeys.MK_f7:
            case Mapper.MapKeys.MK_f8:
            case Mapper.MapKeys.MK_f9:
            case Mapper.MapKeys.MK_f10:
            case Mapper.MapKeys.MK_f11:
            case Mapper.MapKeys.MK_f12:
                key = VK_F1 + (key - Mapper.MapKeys.MK_f1);
                break;
            case Mapper.MapKeys.MK_return:
                key = KeyEvent.VK_ENTER;
                break;
            case Mapper.MapKeys.MK_kpminus:
                key = KeyEvent.VK_MINUS;
                break;
            case Mapper.MapKeys.MK_scrolllock:
                key = KeyEvent.VK_SCROLL_LOCK;
                break;
            case Mapper.MapKeys.MK_pause:
                key = KeyEvent.VK_PAUSE;
                break;
            case Mapper.MapKeys.MK_printscreen:
                key = KeyEvent.VK_PRINTSCREEN;
                break;
            case Mapper.MapKeys.MK_home:
                key = KeyEvent.VK_HOME;
                break;
        }
        return key;
    }

    static boolean ctrAltDel = false;

    static class KeyMapping {

        final int targetScancode;
        final Boolean requireShift; // true for +shift, false for -shift, null for no change

        public KeyMapping(int targetScancode, Boolean requireShift) {
            this.targetScancode = targetScancode;
            this.requireShift = requireShift;
        }
    }

    static class ActiveKey {

        final int targetScancode;
        final int shiftAdjustment; // 1 if +shift, -1 if -shift, 0 if none

        public ActiveKey(int scancode, int shiftAdj) {
            this.targetScancode = scancode;
            this.shiftAdjustment = shiftAdj;
        }
    }

    static final Map<Integer, KeyMapping> unshiftedMap = new HashMap<>();
    static final Map<Integer, KeyMapping> shiftedMap = new HashMap<>();
    static final Map<Integer, ActiveKey> activeKeys = new HashMap<>();
    static boolean hostShiftDown = false;

    static boolean isJISEnabled() {
        return "jpn".equals(System.getProperty("jdosbox.keyboard.layout"));
    }

    static {
        loadJISMappings();
    }

    static void loadJISMappings() {
        if (!isJISEnabled()) return;

        try {
            java.io.InputStream is = KeyboardKey.class.getClassLoader().getResourceAsStream("keyboard_jpn.properties");
            if (is != null) {
                java.util.Properties props = new java.util.Properties();
                props.load(is);
                for (String key : props.stringPropertyNames()) {
                    String value = props.getProperty(key).trim();
                    String[] parts = value.split(",");
                    int scancode = Integer.parseInt(parts[0].trim(), 16);

                    Boolean requireShift = null;
                    if (parts.length > 1) {
                        if ("+shift".equals(parts[1].trim())) requireShift = true;
                        else if ("-shift".equals(parts[1].trim())) requireShift = false;
                    }

                    boolean isShifted = key.endsWith(",+shift");
                    int vk;
                    if (isShifted) {
                        vk = Integer.parseInt(key.substring(0, key.indexOf(',')).trim(), 16);
                        shiftedMap.put(vk, new KeyMapping(scancode, requireShift));
                    } else {
                        vk = Integer.parseInt(key.trim(), 16);
                        unshiftedMap.put(vk, new KeyMapping(scancode, requireShift));
                    }
                }
                is.close();
            }
        } catch (Exception e) {
            // Ignore if we can't load the file
        }
        logger.log(Level.INFO, "jpn: " + "jpn".equals(System.getProperty("jdosbox.keyboard.layout")));
    }

    static void interceptJIS2(KeyEvent e) {
//logger.log(Level.INFO, "keyCode: %02x, keyChar: %04x, modifiers: %04x".formatted(e.getKeyCode(), (int) e.getKeyChar(), e.getModifiersEx()));
        if (e.getKeyCode() == KeyEvent.VK_DELETE && (e.getModifiersEx() & KeyEvent.SHIFT_DOWN_MASK) == KeyEvent.SHIFT_DOWN_MASK) {
            e.setKeyCode(KeyEvent.VK_INSERT);
            return;
        }
        char c = e.getKeyChar();
        int keyCode = switch (c) {
            case '\\' -> KeyEvent.VK_BACK_SLASH;
            case '|' -> KeyEvent.VK_BACK_SLASH;
//            case '[' -> KeyEvent.VK_OPEN_BRACKET;
//            case '{' -> KeyEvent.VK_OPEN_BRACKET;
//            case ']' -> KeyEvent.VK_CLOSE_BRACKET;
//            case '}' -> KeyEvent.VK_CLOSE_BRACKET;
//            case '_' -> KeyEvent.VK_UNDERSCORE;
            default -> KeyEvent.VK_UNDEFINED;
        };
        if (keyCode != KeyEvent.VK_UNDEFINED) {
            e.setKeyCode(keyCode);
        }
    }

    static boolean interceptJIS(KeyEvent event) {
        if (!isJISEnabled()) {
            return false;
        }

        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.VK_SHIFT) {
            hostShiftDown = (event.getID() == KeyEvent.KEY_PRESSED);
            return false;
        }
//logger.log(Level.INFO, "keyCode: " + keyCode + ", " + event.paramString());
        if (event.getID() == KeyEvent.KEY_PRESSED) {
            KeyMapping mapping = hostShiftDown ? shiftedMap.get(keyCode) : unshiftedMap.get(keyCode);
            if (mapping != null) {
                int shiftAdj = 0;
                if (mapping.requireShift != null) {
                    if (mapping.requireShift && !hostShiftDown) {
                        jdos.hardware.Keyboard.KEYBOARD_AddBuffer(0x2A);
                        shiftAdj = 1;
                    } else if (!mapping.requireShift && hostShiftDown) {
                        jdos.hardware.Keyboard.KEYBOARD_AddBuffer(0xAA);
                        shiftAdj = -1;
                    }
                }

                jdos.hardware.Keyboard.KEYBOARD_AddBuffer(mapping.targetScancode);
                activeKeys.put(keyCode, new ActiveKey(mapping.targetScancode, shiftAdj));
                return true;
            }
        } else if (event.getID() == KeyEvent.KEY_RELEASED) {
            ActiveKey active = activeKeys.remove(keyCode);
            if (active != null) {
                jdos.hardware.Keyboard.KEYBOARD_AddBuffer(active.targetScancode | 0x80);

                if (active.shiftAdjustment == 1) {
                    jdos.hardware.Keyboard.KEYBOARD_AddBuffer(0xAA);
                } else if (active.shiftAdjustment == -1) {
                    jdos.hardware.Keyboard.KEYBOARD_AddBuffer(0x2A);
                }
                return true;
            }
        }
        return false;
    }

    static public void checkEvent(Object e) {
        KeyEvent event = (KeyEvent) e;
        interceptJIS2(event);
        if (interceptJIS(event)) {
            return;
        }
        if (JavaMapper.mapper.mods == 3 && event.getKeyCode() == KeyEvent.VK_INSERT && event.getID() == KeyEvent.KEY_PRESSED) {
            ctrAltDel = true;
        }
        if (ctrAltDel && event.getKeyCode() == KeyEvent.VK_INSERT) {
            event.setKeyCode(KeyEvent.VK_DELETE);
        }
//logger.log(Level.INFO, "keyCode: " + event.getKeyCode() + ", " + event.paramString());
        JavaMapper.MAPPER_CheckEvent(e);

        if (ctrAltDel && event.getKeyCode() == KeyEvent.VK_INSERT && event.getID() == KeyEvent.KEY_RELEASED) {
            ctrAltDel = false;
        }
    }

    static public final DefaultKey[] DefaultKeys = {
            new DefaultKey("f1", VK_F1), new DefaultKey("f2", VK_F2), new DefaultKey("f3", VK_F3), new DefaultKey("f4", VK_F4),
            new DefaultKey("f5", VK_F5), new DefaultKey("f6", VK_F6), new DefaultKey("f7", VK_F7), new DefaultKey("f8", VK_F8),
            new DefaultKey("f9", VK_F9), new DefaultKey("f10", VK_F10), new DefaultKey("f11", VK_F11), new DefaultKey("f12", VK_F12),

            new DefaultKey("1", VK_1), new DefaultKey("2", VK_2), new DefaultKey("3", VK_3), new DefaultKey("4", VK_4),
            new DefaultKey("5", VK_5), new DefaultKey("6", VK_6), new DefaultKey("7", VK_7), new DefaultKey("8", VK_8),
            new DefaultKey("9", VK_9), new DefaultKey("0", VK_0),

            new DefaultKey("a", VK_A), new DefaultKey("b", VK_B), new DefaultKey("c", VK_C), new DefaultKey("d", VK_D),
            new DefaultKey("e", VK_E), new DefaultKey("f", VK_F), new DefaultKey("g", VK_G), new DefaultKey("h", VK_H),
            new DefaultKey("i", VK_I), new DefaultKey("j", VK_J), new DefaultKey("k", VK_K), new DefaultKey("l", VK_L),
            new DefaultKey("m", VK_M), new DefaultKey("n", VK_N), new DefaultKey("o", VK_O), new DefaultKey("p", VK_P),
            new DefaultKey("q", VK_Q), new DefaultKey("r", VK_R), new DefaultKey("s", VK_S), new DefaultKey("t", VK_T),
            new DefaultKey("u", VK_U), new DefaultKey("v", VK_V), new DefaultKey("w", VK_W), new DefaultKey("x", VK_X),
            new DefaultKey("y", VK_Y), new DefaultKey("z", VK_Z), new DefaultKey("space", VK_SPACE),

            new DefaultKey("esc", VK_ESCAPE), new DefaultKey("equals", VK_EQUALS), new DefaultKey("grave", VK_BACK_QUOTE),
            new DefaultKey("tab", VK_TAB), new DefaultKey("enter", VK_ENTER), new DefaultKey("bspace", VK_BACK_SPACE),
            new DefaultKey("lbracket", VK_OPEN_BRACKET), new DefaultKey("rbracket", VK_CLOSE_BRACKET),
            new DefaultKey("minus", VK_MINUS), new DefaultKey("capslock", VK_CAPS_LOCK), new DefaultKey("semicolon", VK_SEMICOLON),
            new DefaultKey("quote", VK_QUOTE), new DefaultKey("backslash", VK_BACK_SLASH), new DefaultKey("lshift", VK_SHIFT, true, false, false),
            new DefaultKey("rshift", VK_SHIFT, false, true, false), new DefaultKey("lalt", VK_ALT, true, false, false), new DefaultKey("ralt", VK_ALT, false, true, false),
            new DefaultKey("lctrl", VK_CONTROL, true, false, false), new DefaultKey("rctrl", VK_CONTROL, false, true, false), new DefaultKey("comma", VK_COMMA),
            new DefaultKey("period", VK_PERIOD), new DefaultKey("slash", VK_SLASH), new DefaultKey("printscreen", VK_PRINTSCREEN),
            new DefaultKey("scrolllock", VK_SCROLL_LOCK), new DefaultKey("pause", VK_PAUSE), new DefaultKey("pagedown", VK_PAGE_DOWN),
            new DefaultKey("pageup", VK_PAGE_UP), new DefaultKey("insert", VK_INSERT), new DefaultKey("home", VK_HOME),
            new DefaultKey("delete", VK_DELETE), new DefaultKey("end", VK_END), new DefaultKey("up", VK_UP),
            new DefaultKey("left", VK_LEFT), new DefaultKey("down", VK_DOWN), new DefaultKey("right", VK_RIGHT),
            new DefaultKey("kp_0", VK_NUMPAD0), new DefaultKey("kp_1", VK_NUMPAD1), new DefaultKey("kp_2", VK_NUMPAD2), new DefaultKey("kp_3", VK_NUMPAD3),
            new DefaultKey("kp_4", VK_NUMPAD4), new DefaultKey("kp_5", VK_NUMPAD5), new DefaultKey("kp_6", VK_NUMPAD6), new DefaultKey("kp_7", VK_NUMPAD7),
            new DefaultKey("kp_8", VK_NUMPAD8), new DefaultKey("kp_9", VK_NUMPAD9), new DefaultKey("numlock", VK_NUM_LOCK),
            new DefaultKey("kp_divide", VK_DIVIDE, false, false, true), new DefaultKey("kp_multiply", VK_MULTIPLY, false, false, true),
            new DefaultKey("kp_minus", VK_SUBTRACT, false, false, true), new DefaultKey("kp_plus", VK_ADD),
            new DefaultKey("kp_period", VK_PERIOD, false, false, true), new DefaultKey("kp_enter", VK_ENTER, false, false, true),
            new DefaultKey("lessthan", VK_LESS)
    };
}
