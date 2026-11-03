import { generateKeyPairSync } from "node:crypto";
import { cloudflareTest } from "@cloudflare/vitest-plugin";
import { defineConfig } from "vitest/config";

// 시험용 FCM 서명 키는 시험할 때마다 새로 만든다(저장소에 키를 두지 않는다). 바깥 호출은 시험에서 fetch를 가짜로 바꾼다.
const { privateKey } = generateKeyPairSync("rsa", {
  modulusLength: 2048,
  privateKeyEncoding: { type: "pkcs8", format: "pem" },
  publicKeyEncoding: { type: "spki", format: "pem" },
});

export default defineConfig({
  plugins: [
    cloudflareTest({
      wrangler: { configPath: "./wrangler.jsonc" },
      miniflare: {
        bindings: {
          ANTHROPIC_API_KEY: "EXAMPLE-anthropic-api-key",
          FCM_PROJECT_ID: "example-project",
          FCM_CLIENT_EMAIL: "relay@example-project.iam.gserviceaccount.com",
          FCM_PRIVATE_KEY: privateKey,
        },
      },
    }),
  ],
});
