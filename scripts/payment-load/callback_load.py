"""Isolated Stripe callback benchmark. Never use a production DB or application."""
import argparse
import asyncio
import hashlib
import hmac
import json
import math
import os
from pathlib import Path
import statistics
import time
from urllib.parse import urlsplit

import aiohttp
import pymysql


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def percentile(values, p):
    return sorted(values)[max(0, math.ceil(len(values) * p) - 1)] if values else None


class Database:
    def __init__(self):
        self.settings = dict(host=os.environ.get('LOAD_DB_HOST', '127.0.0.1'),
                             port=int(os.environ['LOAD_DB_PORT']), user=os.environ['LOAD_DB_USER'],
                             password=os.environ['LOAD_DB_PASSWORD'], database=os.environ['LOAD_DB_NAME'],
                             autocommit=True, connect_timeout=5, read_timeout=10,
                             cursorclass=pymysql.cursors.DictCursor)
        require(self.settings['host'] == '127.0.0.1', 'Only an isolated loopback DB is allowed')
        require(self.settings['port'] not in (3306, 13306), 'Production/default DB port forbidden')
        require(self.settings['database'].endswith('_payment_load'), 'DB name must end in _payment_load')

    def read(self, orders, events, scope):
        with pymysql.connect(**self.settings) as conn, conn.cursor() as cur:
            def query(sql, args=()):
                cur.execute(sql, args)
                return cur.fetchall()
            ids = [o['id'] for o in orders]
            owners = [o['owner'] for o in orders]
            refs = ['payment:' + x for x in ids]
            marks = lambda xs: ','.join(['%s'] * len(xs))
            result = {}
            result['orders'] = query(f'SELECT id,creator_id,credits,status,provider FROM payment_order WHERE id IN ({marks(ids)})', ids)
            result['accounts'] = query(f'SELECT owner_id,balance FROM credit_account WHERE owner_id IN ({marks(owners)})', owners)
            result['ledger'] = query(f'SELECT owner_id,COALESCE(SUM(amount),0) balance FROM credit_ledger WHERE owner_id IN ({marks(owners)}) GROUP BY owner_id', owners)
            result['credits'] = query(f"SELECT reference_id,owner_id,amount FROM credit_ledger WHERE entry_type='CREDIT' AND reference_id IN ({marks(refs)})", refs)
            result['batches'] = query(f'SELECT reference,owner_id,original,consumed,adjusted,held FROM p0_credit_batch WHERE reference IN ({marks(refs)})', refs)
            result['inbox'] = query(f'SELECT event_id,status,attempts,last_error FROM payment_webhook_inbox WHERE provider=%s AND merchant_scope=%s AND env_scope=%s AND event_id IN ({marks(events)})', [scope['provider'], scope['merchant'], scope['environment'], *events])
            result['status'] = {r['Variable_name']: int(r['Value']) for r in query("SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_row_lock_time','Innodb_row_lock_waits','Innodb_deadlocks','Uptime')")}
            deadlocks = query("SELECT COUNT,STATUS FROM information_schema.INNODB_METRICS WHERE NAME='lock_deadlocks'")
            require(len(deadlocks) == 1 and deadlocks[0]['STATUS'] == 'enabled', 'Deadlock metric must be enabled')
            result['status']['Innodb_deadlocks'] = int(deadlocks[0]['COUNT'])
            return result


class Benchmark:
    def __init__(self, config, db, output):
        self.config, self.db, self.output = config, db, output
        self.events = {}
        for case in [config['duplicate'], config['out_of_order'], *config['users']]:
            session_ids = set()
            for index, event in enumerate(case['events']):
                body = (config['_root'] / event['body_file']).read_bytes()
                obj = json.loads(body)
                require(obj['id'] == event['id'] and obj.get('livemode') is False, 'Event ID mismatch or non-sandbox event')
                require(obj.get('type') in ('checkout.session.completed', 'checkout.session.async_payment_succeeded', 'checkout.session.async_payment_failed', 'checkout.session.expired'), 'Unsupported event type')
                expected_types = ('checkout.session.completed', 'checkout.session.async_payment_succeeded') if index == 0 else (('checkout.session.async_payment_failed',) if index == 1 else ('checkout.session.expired',))
                require(obj['type'] in expected_types, 'Wrong success/failure/cancel event sequence')
                session_id = obj.get('data', {}).get('object', {}).get('id')
                require(isinstance(session_id, str) and session_id.startswith('cs_'), 'Checkout session ID required')
                session_ids.add(session_id)
                require(event['id'] not in self.events, 'Each manifest event must have a distinct event ID')
                self.events[event['id']] = body
            require(len(session_ids) <= 1, 'Out-of-order events must reference the same checkout session')
        url = urlsplit(config['base_url'])
        require(url.scheme == 'http' and url.hostname == '127.0.0.1' and url.port not in (None, 80, 8080, 5173), 'Use a dedicated isolated app on 127.0.0.1, e.g. port 18080')
        require(not url.username and not url.query and not url.fragment and url.path in ('', '/'), 'Invalid base URL')
        require(config['scope']['provider'] == 'STRIPE_SANDBOX' and config['scope']['environment'] == 'sandbox', 'Sandbox scope required')
        require(config['isolated_app_and_db_confirmed'] is True, 'Confirm the dedicated application uses this isolated DB')
        self.secret = os.environ['LOAD_STRIPE_WEBHOOK_SECRET'].encode()
        require(self.secret.startswith(b'whsec_'), 'Sandbox endpoint signing secret required')
        self.report = {'scope': config['scope'], 'scenarios': []}

    async def snapshot(self, cases):
        return await asyncio.to_thread(self.db.read, cases, [e['id'] for c in cases for e in c['events']], self.config['scope'])

    async def send(self, session, event, gate):
        await gate.wait()
        body = self.events[event['id']]
        timestamp = str(int(time.time()))
        signature = hmac.new(self.secret, timestamp.encode() + b'.' + body, hashlib.sha256).hexdigest()
        start = time.perf_counter()
        try:
            async with session.post(self.config['base_url'].rstrip('/') + '/api/public/stripe/webhook', data=body,
                                    headers={'Content-Type':'application/json', 'Stripe-Signature':f't={timestamp},v1={signature}'},
                                    allow_redirects=False) as response:
                await response.read()
                return {'event':event['id'], 'status':response.status, 'ms':(time.perf_counter()-start)*1000}
        except (aiohttp.ClientError, asyncio.TimeoutError) as error:
            return {'event':event['id'], 'status':0, 'ms':(time.perf_counter()-start)*1000, 'error':type(error).__name__}

    async def burst(self, session, events):
        gate = asyncio.Event()
        tasks = [asyncio.create_task(self.send(session, e, gate)) for e in events]
        await asyncio.sleep(0)  # all tasks reach the same release gate
        gate.set()
        return await asyncio.gather(*tasks)

    def check(self, cases, before, after, event_ids):
        for case in cases:
            order = next(r for r in after['orders'] if r['id'] == case['id'])
            require(order['status'] == 'PAID', f"Order {case['id']} regressed or never became PAID")
            ref = 'payment:' + case['id']
            entries = [r for r in after['credits'] if r['reference_id'] == ref]
            require(len(entries) == 1 and entries[0]['amount'] == case['credits'] and entries[0]['owner_id'] == case['owner'], 'Exactly one correct credit entry required')
            old = next(r['balance'] for r in before['accounts'] if r['owner_id'] == case['owner'])
            new = next(r['balance'] for r in after['accounts'] if r['owner_id'] == case['owner'])
            book = next(r['balance'] for r in after['ledger'] if r['owner_id'] == case['owner'])
            require(new == old + case['credits'] == book, 'Balance delta or ledger/account reconciliation failed')
            batches = [r for r in after['batches'] if r['reference'] == ref]
            require(len(batches) == 1 and batches[0]['original'] == case['credits'] and batches[0]['owner_id'] == case['owner'], 'Missing or wrong entitlement batch')
            require(all(batches[0][k] == 0 for k in ('consumed','adjusted','held')), 'Unexpected entitlement mutation')
        states = {r['event_id']:r['status'] for r in after['inbox']}
        return all(states.get(e) == 'DONE' for e in event_ids)

    async def settle(self, cases, before, events):
        deadline = time.perf_counter() + self.config.get('settle_timeout_seconds', 120)
        stable = None
        while time.perf_counter() < deadline:
            after = await self.snapshot(cases)
            try:
                done = self.check(cases, before, after, events)
            except RuntimeError:
                # Pending processing is expected; final timeout remains a failure.
                done = False
            if done:
                stable = stable or time.perf_counter()
                if time.perf_counter() - stable >= self.config.get('stable_seconds', 5):
                    return after
            else:
                stable = None
            await asyncio.sleep(1)
        self.report['last_snapshot'] = after
        raise RuntimeError('Settlement timeout or accounting assertion failed; inspect last_snapshot and worker logs')

    async def scenario(self, session, name, cases, duplicate=False, ordered=False):
        before = await self.snapshot(cases)
        require(len(before['orders']) == len(cases), 'Missing fixture orders')
        require(not before['credits'] and not before['batches'] and not before['inbox'], 'Fixtures already processed; use fresh orders/events')
        for c in cases:
            row = next(r for r in before['orders'] if r['id'] == c['id'])
            require(row['status'] == 'PENDING' and row['creator_id'] == c['owner'] and row['credits'] == c['credits'] and row['provider'] == 'STRIPE_SANDBOX', 'Invalid order fixture')
            require(any(r['owner_id'] == c['owner'] for r in before['accounts']), 'Initialize fixture credit accounts before running')
        self.report['active_scenario'] = {'name':name, 'before':before}
        start = time.perf_counter()
        initial = [cases[0]['events'][0]] * 50 if duplicate else [c['events'][0] for c in cases]
        replies = await self.burst(session, initial)
        self.report['active_scenario']['responses'] = replies
        require(all(r['status'] in self.config.get('accepted_http_statuses', [200]) for r in replies), 'Unexpected HTTP response: ' + json.dumps(replies))
        after = await self.settle(cases, before, [e['id'] for e in initial])
        if ordered:
            # First wait until success is durable, then send failure AND cancellation sequentially.
            for event in cases[0]['events'][1:]:
                extra = await self.burst(session, [event]); replies += extra
                require(all(r['status'] in self.config.get('accepted_http_statuses', [200]) for r in extra), 'Late callback rejected at HTTP layer')
                after = await self.settle(cases, before, [e['id'] for e in cases[0]['events'][:cases[0]['events'].index(event)+1]])
        delta = {k:after['status'][k]-before['status'][k] for k in ('Innodb_row_lock_time','Innodb_row_lock_waits','Innodb_deadlocks')}
        require(after['status']['Uptime'] >= before['status']['Uptime'] and all(x >= 0 for x in delta.values()), 'DB restarted or counters reset; invalid measurement')
        result = {'name':name, 'requests':len(replies), 'http_p99_ms':percentile([r['ms'] for r in replies], .99),
                  'http_mean_ms':statistics.mean(r['ms'] for r in replies), 'settled_elapsed_seconds':time.perf_counter()-start,
                  'mysql_global_delta':delta, 'responses':replies, 'after':after}
        self.report['scenarios'].append(result)
        self.report.pop('active_scenario', None)
        require(delta['Innodb_deadlocks'] == 0, 'Deadlock detected, including internally retried deadlocks')
        print(json.dumps({k:v for k,v in result.items() if k not in ('responses','after')}, default=str))

    async def run(self):
        cases = [self.config['duplicate'], self.config['out_of_order'], *self.config['users']]
        require(len(self.config['users']) == 100, 'Exactly 100 distinct-user cases required')
        require(len({c['owner'] for c in cases}) == 102 and len({c['id'] for c in cases}) == 102, 'All 102 fixture orders/users must be independent')
        require(len(self.config['out_of_order']['events']) == 3 and all(len(c['events']) == 1 for c in [self.config['duplicate'], *self.config['users']]), 'Expected 1 success, success/failure/cancel, and 100 successes')
        try:
            async with aiohttp.ClientSession(connector=aiohttp.TCPConnector(limit=100, limit_per_host=100), timeout=aiohttp.ClientTimeout(total=30)) as session:
                await self.scenario(session, 'duplicate_50', [self.config['duplicate']], duplicate=True)
                await self.scenario(session, 'success_then_failure_then_cancel', [self.config['out_of_order']], ordered=True)
                await self.scenario(session, 'distinct_users_100', self.config['users'])
            self.report['passed'] = True
        except Exception as error:
            self.report.update(passed=False, error=str(error))
            raise
        finally:
            self.output.write_text(json.dumps(self.report, default=str, indent=2), encoding='utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('manifest', type=Path)
    parser.add_argument('--output', type=Path, default=Path('payment-load-report.json'))
    args = parser.parse_args()
    config = json.loads(args.manifest.read_text(encoding='utf-8-sig'))
    config['_root'] = args.manifest.resolve().parent
    asyncio.run(Benchmark(config, Database(), args.output).run())


if __name__ == '__main__':
    main()
