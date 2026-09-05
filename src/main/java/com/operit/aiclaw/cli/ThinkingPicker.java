package com.operit.aiclaw.cli;

import com.operit.aiclaw.agent.Agent;
import com.operit.aiclaw.llm.ThinkingConfig;
import com.operit.aiclaw.util.ThinkingControlConfig;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Ctrl+A thinking controller driven entirely by the active profile's thinking.N.* fields. */
final class ThinkingPicker {
    private ThinkingPicker() {}

    private record Control(int mode, ThinkingControlConfig.Mode setting) {}
    private record ValueChoice(String label, String value, boolean off) {}

    static void open(ReplInput input, Agent agent, ReplSession session) {
        if (!input.isInteractiveTerminal()) {
            System.out.println("(thinking control requires an interactive terminal)\n");
            return;
        }

        List<String> errors = new ArrayList<>(ThinkingControlConfig.validateModes(session.getThinkingModes()));
        errors.addAll(ThinkingControlConfig.validateControls(session.getThinkingModes(), session.getThinkingControls()));
        if (!errors.isEmpty()) {
            for (String error : errors) System.out.println("[invalid thinking configuration] " + error);
            System.out.println();
            return;
        }

        List<Control> controls = controlsFor(session);
        if (controls.isEmpty()) {
            System.out.println("(Ctrl+A has no configured thinking controls; set thinking.modes and "
                    + "thinking.N.options/value/off in the active profile.)\n");
            return;
        }

        if (controls.size() == 1) {
            activate(input, agent, session, controls.get(0));
            return;
        }

        List<String> labels = new ArrayList<>();
        for (Control control : controls) labels.add(controlLabel(control, session.getThinking()));
        Integer selected = choose(input, "Thinking controls", labels, 0);
        if (selected != null) activate(input, agent, session, controls.get(selected));
    }

    private static List<Control> controlsFor(ReplSession session) {
        ThinkingControlConfig config = session.getThinkingControls();
        List<Control> controls = new ArrayList<>();
        for (int mode : ThinkingControlConfig.parseModes(session.getThinkingModes())) {
            ThinkingControlConfig.Mode setting = config.mode(mode);
            // Modes 2, 4, and 5 have protocol-defined toggle behavior. Modes 1/3 need user options.
            if (mode == 2 || mode == 4 || mode == 5 || (setting != null && !setting.options().isEmpty())) {
                controls.add(new Control(mode, setting));
            }
        }
        return controls;
    }

    private static void activate(ReplInput input, Agent agent, ReplSession session, Control control) {
        switch (control.mode()) {
            case 1, 3 -> chooseValue(input, agent, session, control);
            case 2 -> cycleType(agent, session, control);
            case 4, 5 -> togglePresence(agent, session, control);
            default -> throw new IllegalStateException("unsupported thinking mode: " + control.mode());
        }
    }

    private static void chooseValue(ReplInput input, Agent agent, ReplSession session, Control control) {
        ThinkingControlConfig.Mode setting = control.setting();
        if (setting == null || setting.options().isEmpty()) {
            System.out.println("(" + modeName(control.mode()) + " needs thinking." + control.mode()
                    + ".options in the active profile)\n");
            return;
        }

        List<ValueChoice> choices = new ArrayList<>();
        String off = offValue(control);
        for (String value : setting.options()) {
            boolean isOff = !"remove".equalsIgnoreCase(off) && value.equalsIgnoreCase(off);
            choices.add(new ValueChoice(value + (isOff ? " [off]" : ""), value, isOff));
        }
        if ("remove".equalsIgnoreCase(off)) {
            choices.add(new ValueChoice("off [remove field]", "", true));
        } else if (choices.stream().noneMatch(ValueChoice::off)) {
            choices.add(new ValueChoice("off [" + off + "]", off, true));
        }

        List<String> labels = choices.stream().map(ValueChoice::label).toList();
        String current = session.getThinking().getModeValue(control.mode());
        int selectedIndex = selectionForCurrentValue(choices, current, off);
        Integer selected = choose(input, modeName(control.mode()), labels, selectedIndex);
        if (selected == null) return;

        ValueChoice choice = choices.get(selected);
        if (choice.off()) applyOff(agent, session, control);
        else applyValue(agent, session, control, choice.value());
    }

    private static int selectionForCurrentValue(List<ValueChoice> choices, String current, String off) {
        boolean currentIsOff = isOff(current, off);
        for (int i = 0; i < choices.size(); i++) {
            ValueChoice choice = choices.get(i);
            if (choice.off() && currentIsOff) return i;
            if (!choice.off() && current != null && choice.value().equalsIgnoreCase(current)) return i;
        }
        return 0;
    }

    /** Mode 2 cycles only through profile-provided values; fallback toggles enabled/disabled. */
    private static void cycleType(Agent agent, ReplSession session, Control control) {
        ThinkingControlConfig.Mode setting = control.setting();
        List<String> options = setting == null ? List.of() : setting.options();
        String current = session.getThinking().getModeValue(2);
        if (options.isEmpty()) {
            if (isOff(current, offValue(control))) applyValue(agent, session, control, onValue(control));
            else applyOff(agent, session, control);
            return;
        }

        int index = -1;
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).equalsIgnoreCase(current)) {
                index = i;
                break;
            }
        }
        applyValue(agent, session, control, options.get((index + 1) % options.size()));
    }

    private static void togglePresence(Agent agent, ReplSession session, Control control) {
        String current = session.getThinking().getModeValue(control.mode());
        if (current == null || current.isBlank()) applyValue(agent, session, control, onValue(control));
        else applyOff(agent, session, control);
    }

    private static void applyValue(Agent agent, ReplSession session, Control control, String value) {
        ThinkingConfig thinking = session.getThinking();
        thinking.setModeValue(control.mode(), value);
        session.setThinking(thinking);
        agent.setLlm(session.rebuildLlm());
        System.out.println("[thinking " + modeName(control.mode()) + " -> " + value + "]\n");
    }

    private static void applyOff(Agent agent, ReplSession session, Control control) {
        ThinkingConfig thinking = session.getThinking();
        String off = offValue(control);
        if ("remove".equalsIgnoreCase(off)) {
            thinking.removeMode(control.mode());
            System.out.println("[thinking " + modeName(control.mode()) + " -> off (field removed)]\n");
        } else {
            thinking.setModeValue(control.mode(), off);
            System.out.println("[thinking " + modeName(control.mode()) + " -> " + off + "]\n");
        }
        session.setThinking(thinking);
        agent.setLlm(session.rebuildLlm());
    }

    private static String onValue(Control control) {
        if (control.mode() == 4) return ThinkingConfig.FIXED_TYPE_VALUE;
        if (control.mode() == 5) return ThinkingConfig.FIXED_MODE_VALUE;
        ThinkingControlConfig.Mode setting = control.setting();
        if (setting != null && !setting.value().isBlank()) return setting.value();
        String active = control.mode() == 2 ? "enabled" : null;
        if (active == null) throw new IllegalStateException(modeName(control.mode()) + " has no configured on value");
        return active;
    }

    private static String offValue(Control control) {
        if (control.mode() == 4 || control.mode() == 5) return "remove";
        ThinkingControlConfig.Mode setting = control.setting();
        if (setting != null && !setting.off().isBlank()) return setting.off();
        return switch (control.mode()) {
            case 1 -> "none";
            case 2 -> "disabled";
            case 3 -> "remove";
            default -> throw new IllegalArgumentException("unsupported thinking mode: " + control.mode());
        };
    }

    private static boolean isOff(String value, String off) {
        if (value == null || value.isBlank()) return true;
        return !"remove".equalsIgnoreCase(off) && value.equalsIgnoreCase(off);
    }

    private static String controlLabel(Control control, ThinkingConfig thinking) {
        String value = thinking.getModeValue(control.mode());
        return modeName(control.mode()) + ": " + (value == null || value.isBlank() ? "off" : value);
    }

    private static String modeName(int mode) {
        return switch (mode) {
            case 1 -> "reasoning.effort";
            case 2 -> "thinking.type";
            case 3 -> "thinkingBudget";
            case 4 -> "fixed thinking.type";
            case 5 -> "reasoning.mode";
            default -> "mode " + mode;
        };
    }

    /** Shared compact raw-terminal list interaction used for control and value selection. */
    private static Integer choose(ReplInput input, String title, List<String> labels, int initialSelection) {
        if (labels.isEmpty()) return null;
        System.out.println("\n" + title + ":");
        for (String label : labels) System.out.println("  " + printable(label, 80));
        System.out.println("Up/Down select, Enter apply, Esc cancel");

        int selected = Math.max(0, Math.min(initialSelection, labels.size() - 1));
        boolean ansi = supportsAnsi();
        showSelection(ansi, labels, selected);
        while (true) {
            final ReplInput.Key key;
            try {
                key = input.readKey();
            } catch (IOException e) {
                clearSelection(ansi);
                System.out.println("\n[thinking selection closed]\n");
                return null;
            }
            switch (key) {
                case UP -> {
                    if (selected > 0) {
                        selected--;
                        showSelection(ansi, labels, selected);
                    }
                }
                case DOWN -> {
                    if (selected < labels.size() - 1) {
                        selected++;
                        showSelection(ansi, labels, selected);
                    }
                }
                case ENTER -> {
                    clearSelection(ansi);
                    System.out.println();
                    return selected;
                }
                case ESC, EOF, CTRL_D -> {
                    clearSelection(ansi);
                    System.out.println("\n[thinking selection cancelled]\n");
                    return null;
                }
                case CTRL_A, CTRL_F, CTRL_Y, BACKSPACE, DELETE, LEFT, RIGHT, CHARACTER, OTHER -> {
                    // Nested controls deliberately ignore unrelated keys.
                }
            }
        }
    }

    private static boolean supportsAnsi() {
        String term = System.getenv("TERM");
        return term != null && !term.isBlank() && !"dumb".equalsIgnoreCase(term);
    }

    private static void showSelection(boolean ansi, List<String> labels, int selected) {
        String text = "Selected [" + (selected + 1) + "/" + labels.size() + "]: " + printable(labels.get(selected), 45);
        if (ansi) {
            System.out.print("\r\u001b[2K" + text);
            System.out.flush();
        } else {
            System.out.println(text);
        }
    }

    private static void clearSelection(boolean ansi) {
        if (!ansi) return;
        System.out.print("\r\u001b[2K");
        System.out.flush();
    }

    private static String printable(String value, int max) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            out.append(Character.isISOControl(c) ? ' ' : c);
        }
        String result = out.toString().trim();
        return result.length() <= max ? result : result.substring(0, Math.max(0, max - 3)) + "...";
    }
}