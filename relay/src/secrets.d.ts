// `wrangler secret put`(로컬은 .dev.vars)으로 넣는 비밀값(계약 0장). 넣지 않으면 undefined다.
interface Secrets {
  ANTHROPIC_API_KEY?: string;
  FCM_PROJECT_ID?: string;
  FCM_CLIENT_EMAIL?: string;
  FCM_PRIVATE_KEY?: string;
}
interface Env extends Secrets {}
declare namespace Cloudflare {
  interface Env extends Secrets {}
}
