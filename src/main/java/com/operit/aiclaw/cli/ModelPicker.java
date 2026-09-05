package com.operit.aiclaw.cli;

import com.operit.aiclaw.agent.Agent;
import com.operit.aiclaw.llm.LlmException;
import com.operit.aiclaw.llm.OpenAiClient;

import java.io.IOException;
import java.util.List;

/** Small raw-terminal picker backed by the active endpoint's /models response. */
final class ModelPicker {
    private static final int PAGE_SIZE = 8;

    private ModelPicker() {}

    static void open(ReplInput input, Agent agent, ReplSession session) {
        if (!input.isInteractiveTerminal()) {
            System.out.println("(model picker requires an interactive terminal; use modst-<model> in a script)\n");
            return;
        }

        final String endpoint;
        final List<OpenAiClient.ModelInfo> models;
        try {
            endpoint = session.modelsEndpoint();
            System.out.println(Ansi.info("\n[loading models from " + printable(endpoint, 100) + "]"));
            models = session.listModels();
        } catch (LlmException | IllegalArgumentException e) {
            System.out.println(Ansi.error("[model list unavailable] " + printable(e.getMessage(), 300) + "\\n"));
            return;
        }

        int selected = findSelected(models, agent.app().getModel());
        boolean ansi = supportsAnsi();
        render(endpoint, models, agent.app().getModel());
        showSelection(ansi, models, selected);

        while (true) {
            final ReplInput.Key key;
            try {
                key = input.readKey();
            } catch (IOException e) {
                clearSelection(ansi);
                System.out.println("\n[model picker closed] " + printable(e.getMessage(), 200) + "\n");
                return;
            }

            switch (key) {
                case UP -> {
                    if (selected > 0) {
                        selected--;
                        showSelection(ansi, models, selected);
                    }
                }
                case DOWN -> {
                    if (selected < models.size() - 1) {
                        selected++;
                        showSelection(ansi, models, selected);
                    }
                }
                case ENTER -> {
                    clearSelection(ansi);
                    System.out.println();
                    applySelection(agent, session, models.get(selected));
                    return;
                }
                case ESC, EOF, CTRL_D -> {
                    clearSelection(ansi);
                    System.out.println("\n[model selection cancelled]\n");
                    return;
                }
                case CTRL_F -> {
                    // The list was already loaded from the current endpoint; keep the current selection.
                }
                case CTRL_Y, BACKSPACE, DELETE, LEFT, RIGHT, CHARACTER, OTHER -> {
                    // Picker navigation intentionally only consumes the documented keys.
                }
            }
        }
    }

    private static void render(String endpoint, List<OpenAiClient.ModelInfo> models, String currentModel) {
        System.out.println("Models from " + printable(endpoint, 100));
        int end = Math.min(PAGE_SIZE, models.size());
        for (int i = 0; i < end; i++) {
            OpenAiClient.ModelInfo model = models.get(i);
            String current = model.id().equals(currentModel) ? " [current]" : "";
            System.out.println("  " + modelLabel(model, 80) + current);
        }
        if (end < models.size()) {
            System.out.println("  ... " + (models.size() - end) + " more models");
        }
        System.out.println("Up/Down select, Enter use, Esc cancel");
    }

    /** Update only one status line; unlike a full-frame redraw it is safe when prior output wraps. */
    private static void showSelection(boolean ansi, List<OpenAiClient.ModelInfo> models, int selected) {
        String text = "Selected [" + (selected + 1) + "/" + models.size() + "]: "
                + printable(models.get(selected).id(), 22);
        if (ansi) {
            System.out.print("\r\u001b[2K" + text);
            System.out.flush();
        } else {
            System.out.println(text);
        }
    }

    private static void clearSelection(boolean ansi) {
        if (ansi) {
            System.out.print("\r\u001b[2K");
            System.out.flush();
        }
    }

    private static void applySelection(Agent agent, ReplSession session, OpenAiClient.ModelInfo selected) {
        String old = agent.app().getModel();
        agent.app().setModel(selected.id());
        session.setModel(selected.id());
        System.out.println("[model: " + printable(old == null || old.isBlank() ? "(none)" : old, 50)
                + " -> " + modelLabel(selected, 80) + "] (current session only)\n");
    }

    private static int findSelected(List<OpenAiClient.ModelInfo> models, String currentModel) {
        if (currentModel != null) {
            for (int i = 0; i < models.size(); i++) {
                if (currentModel.equals(models.get(i).id())) return i;
            }
        }
        return 0;
    }

    private static boolean supportsAnsi() {
        String term = System.getenv("TERM");
        return term != null && !term.isBlank() && !"dumb".equalsIgnoreCase(term);
    }

    private static String modelLabel(OpenAiClient.ModelInfo model, int max) {
        String id = printable(model.id(), max);
        String name = printable(model.displayName(), max);
        String text = name.equals(id) ? id : name + " [" + id + "]";
        return printable(text, max);
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