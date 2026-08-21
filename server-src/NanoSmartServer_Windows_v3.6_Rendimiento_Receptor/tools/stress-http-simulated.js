'use strict';

const { performance } = require('node:perf_hooks');

const BASE_URL = String(process.env.NANOSMART_STRESS_URL || 'http://127.0.0.1:28082').replace(/\/$/, '');
const REQUEST_COUNT = Number.parseInt(process.env.NANOSMART_STRESS_REQUESTS || '200', 10);
const BATCH_ITEMS = Number.parseInt(process.env.NANOSMART_STRESS_BATCH_ITEMS || '500', 10);
const CONCURRENCY = Number.parseInt(process.env.NANOSMART_STRESS_HTTP_CONCURRENCY || '20', 10);

async function jsonRequest(pathname, options = {}) {
  const response = await fetch(`${BASE_URL}${pathname}`, {
    ...options,
    headers: { 'Content-Type': 'application/json', ...(options.headers || {}) }
  });
  const body = await response.json();
  if (!response.ok) {
    throw new Error(`${pathname} respondió ${response.status}: ${body.error || 'error'}`);
  }
  return body;
}

function percentile(values, percent) {
  const ordered = [...values].sort((left, right) => left - right);
  return ordered[Math.min(ordered.length - 1, Math.floor(ordered.length * percent))] || 0;
}

async function run() {
  const suffix = String(Date.now()).slice(-9).padStart(9, '0');
  const first = await jsonRequest('/api/admin/installations', {
    method: 'POST',
    body: JSON.stringify({ imei: `800001${suffix}`, name: 'Estrés HTTP A' })
  });
  const second = await jsonRequest('/api/admin/installations', {
    method: 'POST',
    body: JSON.stringify({ imei: `800002${suffix}`, name: 'Estrés HTTP B' })
  });
  const accessTokens = Array.from(
    { length: BATCH_ITEMS },
    (_, index) => index % 2 === 0 ? first.accessToken : second.accessToken
  );
  const body = JSON.stringify({ accessTokens });
  const latencies = [];
  let nextRequest = 0;
  let completed = 0;
  const startedAt = performance.now();

  async function worker() {
    while (true) {
      const requestNumber = nextRequest;
      nextRequest += 1;
      if (requestNumber >= REQUEST_COUNT) return;
      const requestStartedAt = performance.now();
      const result = await jsonRequest('/api/app/batch/status', { method: 'POST', body });
      if (result.count !== BATCH_ITEMS) {
        throw new Error(`Respuesta incompleta: ${result.count}/${BATCH_ITEMS}`);
      }
      latencies.push(performance.now() - requestStartedAt);
      completed += 1;
    }
  }

  await Promise.all(Array.from({ length: CONCURRENCY }, () => worker()));
  const elapsedMs = performance.now() - startedAt;
  const health = await jsonRequest('/api/health');
  console.log(JSON.stringify({
    simulated: true,
    realFirebaseRequests: 0,
    serverVersion: health.version,
    requests: completed,
    batchItems: BATCH_ITEMS,
    panelStatusesReturned: completed * BATCH_ITEMS,
    concurrency: CONCURRENCY,
    elapsedMs: Number(elapsedMs.toFixed(1)),
    requestsPerSecond: Number((completed * 1000 / elapsedMs).toFixed(1)),
    statusesPerSecond: Number((completed * BATCH_ITEMS * 1000 / elapsedMs).toFixed(1)),
    latencyP50Ms: Number(percentile(latencies, 0.5).toFixed(1)),
    latencyP95Ms: Number(percentile(latencies, 0.95).toFixed(1)),
    latencyMaximumMs: Number(Math.max(...latencies).toFixed(1))
  }, null, 2));
}

run().catch((error) => {
  console.error(error.stack || error.message);
  process.exitCode = 1;
});
