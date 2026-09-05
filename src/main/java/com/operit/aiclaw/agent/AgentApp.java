package com.operit.aiclaw.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent App configuration object. Maps one-to-one to an {@code agents/*.yaml} file.
 *
 * <p>Example yaml:
 * <pre>
 *   name: coder
 *   description: Coding assistant
 *   system_prompt: |
 *     You are a coding assistant...
 *   tools:
 *     - read_file
 *     - write_file
 *     - shell
 *   options:
 *     temperature: 0.2
 *     max_tokens: 2048
 *   greeting: Hello, I am the coder agent.
 * </pre>
 */
public class AgentApp {

    private String name;
    private String description;
    private String model;
    private String systemPrompt;
    private List<String> tools = new ArrayList<>();
    private Map<String, Object> options = new LinkedHashMap<>();
    private String greeting;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

    public List<String> getTools() { return tools; }
    public void setTools(List<String> tools) {
        this.tools = tools == null ? new ArrayList<>() : tools;
    }

    public Map<String, Object> getOptions() { return options; }
    public void setOptions(Map<String, Object> options) {
        this.options = options == null ? new LinkedHashMap<>() : options;
    }

    public String getGreeting() { return greeting; }
    public void setGreeting(String greeting) { this.greeting = greeting; }

    @Override
    public String toString() {
        return "AgentApp{name='" + name + "', model='" + model + "', tools=" + tools + "}";
    }
}