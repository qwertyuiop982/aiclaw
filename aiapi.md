import OpenAI from "openai";

const openai = new OpenAI({
        baseURL: 'URL',
        apiKey: process.env.API_KEY,
});

async function main() {
  const completion = await openai.chat.completions.create({
    messages: [{ role: "system", content: "You are a helpful assistant." }],
    model: "<model>",
    thinking: {"type": "enabled"},
    reasoning_effort: "high",
    stream: false,
  });

  console.log(completion.choices[0].message.content);
}

main();



deepseek thinking: low/high
OpenAI: GPT5.6: none / low / medium / high / xhigh / max GPT5.6>: none / low / medium / high / xhigh
(Need to remove thinking: { "type": "enabled" },Use only    reasoning_effort)

kimi: k3: low,high,max k2.7（thinking.type=enabled，Unable to unthink）k2.6（thinking.type）

MiniMax: m2.5,2m2.7（thinking.type=enabled，Unable to unthink）m3: thinking.type
mimo: low / medium / high

glm: 5.1: thinking.type 5.2/5.3: high/max