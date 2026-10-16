import type { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify';
import { getDefaultTimeoutMs, getModelName } from '../../core/settings.js';
import { appendMessage, linkLatestUserMessageToJob, resolveThread } from '../../core/threads.js';
import {
  anthropicErrors,
  anthropicPingFrame,
  eventFrame,
  messageContentFrames,
  messageStartFrame,
  newMessageId,
  parseAnthropicRequest,
  renderMessage,
} from '../../protocol/anthropic.js';
import { estimateTokens } from '../../protocol/types.js';
import type { ServerContext } from '../app.js';
import { checkKeyLimits, getAnthropicKey } from '../auth.js';
import { serveNonStream, serveStream } from '../serve.js';

/**
 * 兼容说明：同时挂载 /v1/messages 与 /v1/v1/messages。
 * 有些客户端把 ANTHROPIC_BASE_URL 配成以 /v1 结尾（OpenAI 习惯），SDK 会再拼
 * /v1/messages，实际请求成 /v1/v1/messages——挂个别名让两种配法都能用。
 */
export function registerAnthropicRoutes(app: FastifyInstance, ctx: ServerContext): void {
  const messagesHandler = async (req: FastifyRequest, reply: FastifyReply) => {
    const key = getAnthropicKey(ctx.db, req);
    if (key === null) return reply.code(401).send(anthropicErrors.invalidKey());

    const parsed = parseAnthropicRequest(req.body);
    if (!parsed.ok) return reply.code(400).send(anthropicErrors.badRequest(parsed.message));

    const violation = checkKeyLimits(ctx.db, ctx.queue, key);
    if (violation === 'quota') return reply.code(429).send(anthropicErrors.quota());
    if (violation === 'pending') return reply.code(429).send(anthropicErrors.tooManyPending());

    const model = parsed.value.prompt.model || getModelName(ctx.db);
    const { thread } = resolveThread(ctx.db, {
      keyId: key.id,
      protocol: 'anthropic',
      model,
      system: parsed.value.prompt.system,
      messages: parsed.value.prompt.messages,
    });

    const timeoutMs = key.timeoutMs ?? getDefaultTimeoutMs(ctx.db);
    const job = ctx.queue.create({
      keyId: key.id,
      threadId: thread.id,
      protocol: 'anthropic',
      mode: parsed.value.stream ? 'stream' : 'non_stream',
      requestJson: JSON.stringify(req.body ?? {}),
      timeoutMs,
    });
    linkLatestUserMessageToJob(ctx.db, thread.id, job.id);

    const id = newMessageId();
    const promptTokens = estimateTokens(
      [parsed.value.prompt.system ?? '', ...parsed.value.prompt.messages.map((m) => m.content)].join('\n'),
    );
    const onDelivered = (content: string): void => {
      appendMessage(ctx.db, { threadId: thread.id, role: 'assistant', content, source: 'api', jobId: job.id });
    };

    if (parsed.value.stream) {
      await serveStream({
        reply,
        queue: ctx.queue,
        job,
        onDelivered,
        renderer: {
          initial: messageStartFrame({ id, model, inputTokens: promptTokens }),
          content: (content) =>
            messageContentFrames({
              id,
              model,
              content,
              inputTokens: promptTokens,
              outputTokens: estimateTokens(content),
            }),
          ping: anthropicPingFrame(),
          error: eventFrame('error', anthropicErrors.timeout()),
          pingMs: ctx.ssePingMs,
        },
      });
      return reply;
    }

    await serveNonStream({
      reply,
      queue: ctx.queue,
      job,
      onDelivered,
      json: {
        success: (content) =>
          renderMessage({
            id,
            model,
            content,
            inputTokens: promptTokens,
            outputTokens: estimateTokens(content),
          }),
        timeout: anthropicErrors.timeout,
        timeoutStatus: 529,
      },
    });
    return reply;
  };

  /** Claude Code 会调用 count_tokens 估算上下文；按字符估算返回即可 */
  const countTokensHandler = async (req: FastifyRequest, reply: FastifyReply) => {
    const key = getAnthropicKey(ctx.db, req);
    if (key === null) return reply.code(401).send(anthropicErrors.invalidKey());

    const parsed = parseAnthropicRequest(req.body);
    if (!parsed.ok) return reply.code(400).send(anthropicErrors.badRequest(parsed.message));

    const text = [parsed.value.prompt.system ?? '', ...parsed.value.prompt.messages.map((m) => m.content)].join('\n');
    return { input_tokens: estimateTokens(text) };
  };

  for (const url of ['/v1/messages', '/v1/v1/messages']) {
    app.route({ method: 'POST', url, handler: messagesHandler });
  }
  for (const url of ['/v1/messages/count_tokens', '/v1/v1/messages/count_tokens']) {
    app.route({ method: 'POST', url, handler: countTokensHandler });
  }
}
