import type { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify';
import { getDefaultTimeoutMs, getModelName } from '../../core/settings.js';
import { appendMessage, linkLatestUserMessageToJob, resolveThread } from '../../core/threads.js';
import {
  SSE_PING_COMMENT,
  completionContentFrames,
  completionRoleFrame,
  newCompletionId,
  openaiErrors,
  parseOpenAIRequest,
  renderCompletion,
  renderModel,
  renderModels,
  sseFrame,
} from '../../protocol/openai.js';
import { estimateTokens } from '../../protocol/types.js';
import type { ServerContext } from '../app.js';
import { checkKeyLimits, getOpenAIKey } from '../auth.js';
import { serveNonStream, serveStream } from '../serve.js';

export function registerOpenAIRoutes(app: FastifyInstance, ctx: ServerContext): void {
  // 别名：容错 base URL 结尾多带 /v1 的写法（/v1/v1/chat/completions）
  const chatCompletionsHandler = async (req: FastifyRequest, reply: FastifyReply) => {
    const key = getOpenAIKey(ctx.db, req);
    if (key === null) return reply.code(401).send(openaiErrors.invalidKey());

    const parsed = parseOpenAIRequest(req.body);
    if (!parsed.ok) return reply.code(400).send(openaiErrors.badRequest(parsed.message));

    const violation = checkKeyLimits(ctx.db, ctx.queue, key);
    if (violation === 'quota') return reply.code(429).send(openaiErrors.quota());
    if (violation === 'pending') return reply.code(429).send(openaiErrors.tooManyPending());

    const model = parsed.value.prompt.model || getModelName(ctx.db);
    const { thread } = resolveThread(ctx.db, {
      keyId: key.id,
      protocol: 'openai',
      model,
      system: parsed.value.prompt.system,
      messages: parsed.value.prompt.messages,
    });

    const timeoutMs = key.timeoutMs ?? getDefaultTimeoutMs(ctx.db);
    const job = ctx.queue.create({
      keyId: key.id,
      threadId: thread.id,
      protocol: 'openai',
      mode: parsed.value.stream ? 'stream' : 'non_stream',
      requestJson: JSON.stringify(req.body ?? {}),
      timeoutMs,
    });
    linkLatestUserMessageToJob(ctx.db, thread.id, job.id);

    const id = newCompletionId();
    const created = Math.floor(Date.now() / 1000);
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
          initial: completionRoleFrame({ id, created, model }),
          content: (content) =>
            completionContentFrames({
              id,
              created,
              model,
              content,
              promptTokens,
              completionTokens: estimateTokens(content),
              includeUsage: parsed.value.includeUsage,
            }),
          ping: SSE_PING_COMMENT,
          error: sseFrame(openaiErrors.timeout()),
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
          renderCompletion({
            id,
            created,
            model,
            content,
            promptTokens,
            completionTokens: estimateTokens(content),
            includeUsage: false,
          }),
        timeout: openaiErrors.timeout,
        timeoutStatus: 504,
      },
    });
    return reply;
  };

  for (const url of ['/v1/chat/completions', '/v1/v1/chat/completions']) {
    app.route({ method: 'POST', url, handler: chatCompletionsHandler });
  }

  app.get('/v1/models', async (req, reply) => {
    const key = getOpenAIKey(ctx.db, req);
    if (key === null) return reply.code(401).send(openaiErrors.invalidKey());
    return renderModels(getModelName(ctx.db), Math.floor(Date.now() / 1000));
  });

  app.get('/v1/models/:id', async (req, reply) => {
    const key = getOpenAIKey(ctx.db, req);
    if (key === null) return reply.code(401).send(openaiErrors.invalidKey());
    return renderModel(getModelName(ctx.db), Math.floor(Date.now() / 1000));
  });
}
