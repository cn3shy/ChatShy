import type { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify';
import { getDefaultTimeoutMs, getModelName } from '../../core/settings.js';
import { requestSource } from '../../core/source.js';
import { appendMessage, linkLatestUserMessageToJob, resolveThread } from '../../core/threads.js';
import {
  SSE_PING_COMMENT,
  completionDeltaFrame,
  completionFinishFrames,
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
import { requireCallerKey } from '../auth.js';
import { serveNonStream, serveStream } from '../serve.js';

export function registerOpenAIRoutes(app: FastifyInstance, ctx: ServerContext): void {
  // 别名：容错 base URL 结尾多带 /v1 的写法（/v1/v1/chat/completions）
  const chatCompletionsHandler = async (req: FastifyRequest, reply: FastifyReply) => {
    if (!requireCallerKey(ctx.db, req)) return reply.code(401).send(openaiErrors.invalidKey());

    const parsed = parseOpenAIRequest(req.body);
    if (!parsed.ok) return reply.code(400).send(openaiErrors.badRequest(parsed.message));

    const model = parsed.value.prompt.model || getModelName(ctx.db);
    const { thread } = resolveThread(ctx.db, {
      source: requestSource(req),
      protocol: 'openai',
      model,
      system: parsed.value.prompt.system,
      messages: parsed.value.prompt.messages,
    });

    const job = ctx.queue.create({
      threadId: thread.id,
      protocol: 'openai',
      mode: parsed.value.stream ? 'stream' : 'non_stream',
      requestJson: JSON.stringify(req.body ?? {}),
      timeoutMs: getDefaultTimeoutMs(ctx.db),
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
        typewriterTickMs: ctx.typewriterTickMs,
        renderer: {
          initial: completionRoleFrame({ id, created, model }),
          open: '',
          delta: (text) => completionDeltaFrame({ id, created, model }, text),
          finish: (fullText) =>
            completionFinishFrames({
              id,
              created,
              model,
              content: fullText,
              promptTokens,
              completionTokens: estimateTokens(fullText),
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
    if (!requireCallerKey(ctx.db, req)) return reply.code(401).send(openaiErrors.invalidKey());
    return renderModels(getModelName(ctx.db), Math.floor(Date.now() / 1000));
  });

  app.get('/v1/models/:id', async (req, reply) => {
    if (!requireCallerKey(ctx.db, req)) return reply.code(401).send(openaiErrors.invalidKey());
    return renderModel(getModelName(ctx.db), Math.floor(Date.now() / 1000));
  });
}
