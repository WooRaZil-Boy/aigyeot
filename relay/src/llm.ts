// ④ 상황판 대리 호출(계약 1.4 `POST /v1/llm`). 키를 공개 APK에 넣지 않으려고 중계가 Claude API를 부른다.
// 단순화: 계약은 @anthropic-ai/sdk 1.x를 적었지만 이번 판은 내려받기 허락 범위 밖이라 Messages API를 fetch로 직접 부른다.
import type { LlmRequest, LlmResponse } from "./protocol";
import { HttpError } from "./util";

export const PROMPT_VERSION = "p6";
const API_URL = "https://api.anthropic.com/v1/messages";
const TIMEOUT_MS = 8000; // G6: 재시도 0회

export const SYSTEM_PROMPT = `너는 차 안 운전자에게 뒷좌석 아기 상황을 알려 주는 음성 안내다.
- 아기·차 상황의 사실(수유·잠·기저귀·울음·카시트·실내 온도·쉼터·잘 통한 소리)을 물으면 answer 도구로 주제를 고른다(1~3개, 물은 순서대로): feeding(수유·먹은 것), sleep(잠·지난밤), diaper(기저귀), cry(울음), carseat(카시트), cabin_temp(실내 온도), next_rest(쉼터·수유실), sound_stats(잘 통한 소리). 이때 글은 쓰지 않는다. 차가 상태값으로 정해진 문장을 말한다.
- 왜 우는지처럼 사실이 아닌 질문이면 한국어로 두 문장 이내로 답하고, answer 도구로 why(우는 이유) 또는 unknown(그 밖)을 고른다.
- 글에는 숫자를 쓰지 않는다(아라비아 숫자도, "세 번"·"두 시간" 같은 말도).
- 원인은 단정하지 않고 "~일 수 있어요"처럼 추정으로 말한다.
- "잠들었어요", "배고파서 울어요", "1분 안에 진정돼요"처럼 아기의 지금 상태를 단정하지 않는다.
- 뒤를 돌아보거나 손을 뻗으라고 하지 않는다. 차에서 더 재우라고 하지 않는다.
- 소리 바꾸기는 운전자가 분명히 요청했을 때만 switch_sound로 한다.`;

const strictObject = (properties: object, required: string[]) => ({
  type: "object",
  properties,
  required,
  additionalProperties: false,
});

export const TOOLS = [
  {
    name: "switch_sound",
    description: "운전자가 뒷좌석 소리를 바꿔 달라고 분명히 말했을 때만 쓴다.",
    strict: true,
    input_schema: strictObject(
      { method: { type: "string", enum: ["white_noise", "womb", "lullaby", "parent_song"] } },
      ["method"],
    ),
  },
  {
    name: "add_waypoint",
    description: "운전자가 다음 쉼터에 들르자고 할 때만 쓴다. rest_id는 상태값 next_rest.id다.",
    strict: true,
    input_schema: strictObject({ rest_id: { type: "string" } }, ["rest_id"]),
  },
  {
    name: "stop_soothing",
    description: "운전자가 그만하라고 하거나 직접 하겠다고 할 때 쓴다.",
    strict: true,
    input_schema: strictObject({}, []),
  },
  {
    name: "answer",
    description: "아기·차 상황 질문에 글로 답할 때 같은 질문의 주제를 고른다. 차는 글이 검사를 통과하지 못하면 이 주제로 정해진 문장을 말한다.",
    strict: true,
    input_schema: strictObject(
      {
        topics: {
          type: "array",
          items: { type: "string", enum: ["feeding", "sleep", "diaper", "cry", "carseat", "cabin_temp", "next_rest", "sound_stats", "why", "unknown"] },
        },
      },
      ["topics"],
    ),
  },
];

type Block = { type: string; text?: string; name?: string; input?: Record<string, unknown> };
type ApiMessage = { model?: string; stop_reason?: string; content?: Block[]; usage?: { input_tokens?: number; output_tokens?: number } };

const isTimeout = (e: unknown) => (e as Error)?.name === "TimeoutError" || (e as Error)?.name === "AbortError";

export async function askClaude(env: Env, req: LlmRequest): Promise<LlmResponse> {
  const started = Date.now();
  let data: ApiMessage;
  let status: number;
  try {
    const res = await fetch(API_URL, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-api-key": env.ANTHROPIC_API_KEY!,
        "anthropic-version": "2026-10-21",
      },
      // temperature·top_p·top_k는 2026-10-21 판에서 400이라 보내지 않는다. thinking은 between_tools 하나만(다른 필드와 같이 보내면 400, L102).
      body: JSON.stringify({
        model: env.LLM_MODEL,
        max_tokens: 1024,
        thinking: { type: "between_tools" },
        output_config: { effort: "low" },
        system: SYSTEM_PROMPT,
        tools: TOOLS,
        tool_choice: { type: "auto" },
        messages: [
          { role: "user", content: `상태값(JSON):\n${JSON.stringify(req.state)}\n\n운전자 질문: ${req.question}` },
        ],
      }),
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
    status = res.status;
    data = (await res.json()) as ApiMessage;
  } catch (e) {
    if (isTimeout(e)) throw new HttpError(504, "llm_timeout");
    throw new HttpError(502, "llm_upstream", "Anthropic 응답을 읽지 못함");
  }
  const latency = Date.now() - started;
  // 로그는 지연·종료 사유·토큰 수만(6.6). 질문·상태값·답은 남기지 않는다.
  console.log("llm", status, latency, data.stop_reason, data.usage?.input_tokens, data.usage?.output_tokens);
  if (status < 200 || status >= 300) throw new HttpError(502, "llm_upstream", `Anthropic ${status}`);
  if (data.stop_reason === "refusal") throw new HttpError(422, "llm_refused");
  // end_turn·tool_use만 정상이다. 잘린 응답(max_tokens, pause_turn 등)은 쓰지 않는다(L44).
  if (data.stop_reason !== "end_turn" && data.stop_reason !== "tool_use") {
    throw new HttpError(502, "llm_upstream", `stop_reason ${data.stop_reason}`);
  }
  const blocks = Array.isArray(data.content) ? data.content : [];
  const text = blocks
    .filter((b) => b.type === "text")
    .map((b) => b.text ?? "")
    .join("");
  const tool_calls = blocks
    .filter((b) => b.type === "tool_use")
    .map((b) => ({ name: String(b.name), input: b.input ?? {} }));
  if (!text && !tool_calls.length) throw new HttpError(502, "llm_upstream", "빈 답");
  return {
    text,
    tool_calls,
    stop_reason: data.stop_reason,
    model: data.model ?? env.LLM_MODEL,
    prompt_version: PROMPT_VERSION,
    latency_ms: latency,
  };
}
