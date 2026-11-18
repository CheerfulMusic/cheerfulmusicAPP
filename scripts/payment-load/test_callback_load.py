"""Client/validator tests only; these do not certify the payment application."""
import asyncio
import hashlib
import hmac
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import aiohttp
from aiohttp import web
from callback_load import Benchmark, Database, percentile


class HarnessTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.env = patch.dict(os.environ, {'LOAD_STRIPE_WEBHOOK_SECRET':'whsec_isolated_test'})
        self.env.start()
        self.requests = 0
        async def callback(request):
            body = await request.read()
            fields = dict(x.split('=',1) for x in request.headers['Stripe-Signature'].split(','))
            expected = hmac.new(b'whsec_isolated_test',fields['t'].encode()+b'.'+body,hashlib.sha256).hexdigest()
            self.assertEqual(fields['v1'],expected)
            self.requests += 1
            return web.json_response({'received':True})
        app = web.Application()
        app.router.add_post('/api/public/stripe/webhook',callback)
        self.runner = web.AppRunner(app); await self.runner.setup()
        site = web.TCPSite(self.runner,'127.0.0.1',0); await site.start()
        port = site._server.sockets[0].getsockname()[1]
        event={'id':'evt_test','body_file':'event.json'}
        (self.root/'event.json').write_text(json.dumps({'id':'evt_test','livemode':False,'type':'checkout.session.completed','data':{'object':{'id':'cs_test'}}}))
        self.config={'_root':self.root,'base_url':f'http://127.0.0.1:{port}', 'scope':{'provider':'STRIPE_SANDBOX','merchant':'test','environment':'sandbox'},'isolated_app_and_db_confirmed':True,'duplicate':{'events':[event]},'out_of_order':{'events':[]},'users':[]}
        self.benchmark=Benchmark(self.config,None,self.root/'report.json')

    async def asyncTearDown(self):
        await self.runner.cleanup();self.env.stop();self.temp.cleanup()

    async def test_fifty_real_http_requests_and_signature(self):
        async with aiohttp.ClientSession(connector=aiohttp.TCPConnector(limit=100)) as session:
            replies=await self.benchmark.burst(session,[{'id':'evt_test'}]*50)
        self.assertEqual(self.requests,50)
        self.assertTrue(all(r['status']==200 for r in replies))
        self.assertEqual(len(replies),50)

    async def test_refuses_daily_application(self):
        self.config['base_url']='http://127.0.0.1:8080'
        with self.assertRaisesRegex(RuntimeError,'dedicated isolated app'):
            Benchmark(self.config,None,self.root/'report.json')

    async def test_refuses_production_database(self):
        with patch.dict(os.environ,{'LOAD_DB_PORT':'13306','LOAD_DB_USER':'test','LOAD_DB_PASSWORD':'test','LOAD_DB_NAME':'qingfeng_music'}):
            with self.assertRaises(RuntimeError):Database()

    async def test_accounting_rejects_double_credit_and_state_regression(self):
        case={'id':'order','owner':'owner','credits':100}
        before={'accounts':[{'owner_id':'owner','balance':4}]}
        after={'orders':[{'id':'order','status':'PAID'}], 'credits':[{'reference_id':'payment:order','owner_id':'owner','amount':100}], 'accounts':[{'owner_id':'owner','balance':104}], 'ledger':[{'owner_id':'owner','balance':104}], 'batches':[{'reference':'payment:order','owner_id':'owner','original':100,'consumed':0,'adjusted':0,'held':0}], 'inbox':[{'event_id':'evt_test','status':'DONE'}]}
        self.assertTrue(self.benchmark.check([case],before,after,['evt_test']))
        after['accounts'][0]['balance']=204
        with self.assertRaisesRegex(RuntimeError,'Balance delta'):self.benchmark.check([case],before,after,['evt_test'])
        after['accounts'][0]['balance']=104;after['orders'][0]['status']='CANCELLED'
        with self.assertRaisesRegex(RuntimeError,'regressed'):self.benchmark.check([case],before,after,['evt_test'])

    async def test_percentile_nearest_rank(self):
        self.assertEqual(percentile(list(range(1,101)),.99),99)


if __name__=='__main__':unittest.main()
