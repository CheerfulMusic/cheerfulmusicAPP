// Run from the app project. Supply the prefix from the signed app's application-identifier entitlement.
const prefix = process.argv[2]
if (process.argv.length !== 3 || !/^[A-Z0-9]{10}$/.test(prefix ?? '')) {
  console.error('Usage: node scripts/wechat-aasa.mjs <10-character Apple application identifier prefix>')
  process.exitCode = 1
} else {
  const association = {
    applinks: { details: [{ appIDs: [prefix + '.com.qingfeng.music'], components: [{ '/': '/app/wechat/*' }] }] },
  }
  process.stdout.write(JSON.stringify(association, null, 2) + '\n')
}
