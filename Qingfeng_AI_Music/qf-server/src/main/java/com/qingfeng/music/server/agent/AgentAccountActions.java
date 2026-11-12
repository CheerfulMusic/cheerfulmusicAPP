package com.qingfeng.music.server.agent;

import com.qingfeng.music.agent.api.AgentApplicationActions;
import com.qingfeng.music.agent.api.SongCard;
import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.credits.*;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.infrastructure.persistence.usage.JdbcUsageBudget;
import com.qingfeng.music.server.analytics.AnalyticsDataErasureService;
import com.qingfeng.music.server.payment.PaymentService;
import com.qingfeng.music.server.payment.PayPalPaymentService;
import com.qingfeng.music.server.security.CreatorPrincipal;
import com.qingfeng.music.usage.UsageBudget;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** Account facts come from the same services as the account page; credentials never enter chat. */
@Service @Profile({"mysql","test"})
@ConditionalOnProperty(name="qingfeng.agent.chat.enabled",havingValue="true")
public class AgentAccountActions implements AgentApplicationActions {
    private final CreditsLedger credits;
    private final CreditsPricing pricing;
    private final RewriteCharges rewrites;
    private final JdbcUsageBudget usage;
    private final PaymentService payments;
    private final PayPalPaymentService paypal;
    private final IdentityApi identity;
    private final ReferralRewards referrals;
    private final AnalyticsDataErasureService erasure;
    private final JdbcTemplate db;
    public AgentAccountActions(CreditsLedger credits, CreditsPricing pricing, RewriteCharges rewrites,
            JdbcUsageBudget usage, PaymentService payments, PayPalPaymentService paypal, IdentityApi identity,
            ReferralRewards referrals, AnalyticsDataErasureService erasure, JdbcTemplate db) {
        this.credits=credits;this.pricing=pricing;this.rewrites=rewrites;this.usage=usage;
        this.payments=payments;this.paypal=paypal;this.identity=identity;this.referrals=referrals;
        this.erasure=erasure;this.db=db;
    }
    @Override public List<Capability> capabilities() {
        return List.of(
            cap("get_credits","查询当前账号积分余额和真实生成价格","{}","read"),
            cap("list_credit_entries","查看本人最近积分收支明细","{}","read"),
            cap("get_usage","查询音乐、歌词、封面等每日额度和在途数量","{}","read"),
            cap("get_account","查看本人登录及恢复设置状态；不读取密码或恢复码","{}","read"),
            cap("get_referral","获取本人的邀请链接","{}","read"),
            cap("accept_referral","使用邀请人提供的邀请码；仍按现有首次登录规则处理","{code:邀请码}","write"),
            cap("list_orders","查看本人充值订单，包含现有支付渠道","{}","read"),
            cap("get_order","核对指定充值订单真实状态，不自动发起扣款","{orderId:真实订单ID,provider:paypal|airwallex}","read"),
            cap("close_order","按现有规则关闭本人未付款PayPal订单","{orderId:真实订单ID}","write"),
            cap("payment_catalog","查看当前开放的充值套餐及价格","{}","read"),
            cap("recharge","在对话中打开现有充值收银台；用户选择套餐并授权实际支付","{}","native"),
            cap("photo_song","在对话内打开真实照片写歌面板，用户选择图片、查看价格后提交；打开窗口不会生成或扣费","{}","native"),
            cap("login","打开登录窗口，由用户本人完成验证","{}","native"),
            cap("logout","打开账号窗口执行退出登录","{}","native"),
            cap("account_settings","在对话中打开账号设置，可查看或生成恢复码、恢复账号；秘密仅在原生窗口显示","{}","native"),
            cap("privacy_settings","打开匿名分析同意设置","{}","native"),
            cap("erase_analytics","请求删除或匿名化本人历史分析数据，需确认且受服务开关限制","{}","destructive")
        );
    }
    private static Capability cap(String id,String description,String parameters,String effect){return new Capability(id,description,parameters,effect);}
    @Override public Result execute(String owner,String conversation,String action,Map<String,Object> args,String text,String key,SongCard current) {
        if(owner==null||owner.isBlank())throw new DomainException("LOGIN_REQUIRED","请先登录账号。");
        try {
            return switch(action) {
                case "get_credits" -> credits(owner);
                case "list_credit_entries" -> entries(owner);
                case "get_usage" -> usage(owner);
                case "get_account" -> account(owner);
                case "get_referral" -> {
                    String code=referrals.code(owner);
                    yield result("这是你的邀请信息。邀请奖励以首次登录的真实结果为准。","referral",
                        List.of(new Item(code,"邀请好友","邀请码："+code,Map.of("invitePath","/account?invite="+code))),List.of(),action);
                }
                case "accept_referral" -> {
                    referrals.accept(owner,required(args,"code"));
                    yield result("邀请关系已记录，奖励将按首次登录规则处理。","account",List.of(),List.of(new Command("登录账号","","login")),action);
                }
                case "list_orders" -> orders(owner);
                case "get_order" -> order(owner,args,false);
                case "close_order" -> order(owner,args,true);
                case "payment_catalog" -> catalog();
                case "recharge" -> nativeResult("请在对话内的充值窗口选择套餐并完成支付授权。支付结果将由服务端核对。","打开充值窗口","recharge",action);
                case "photo_song" -> result("请在照片写歌窗口选择图片，查看生成费用并提交。打开窗口不会开始生成；完成后可以返回对话继续创作。","photo",List.of(),List.of(new Command("打开照片写歌","","photo")),action);
                case "login" -> nativeResult("请在登录窗口完成账号验证。","登录账号","login",action);
                case "logout" -> nativeResult("可以在账号窗口退出当前账号。","打开账号窗口","account",action);
                case "account_settings" -> nativeResult("账号设置将在独立窗口显示。请勿把密码、验证码或恢复码发送到聊天里。","打开账号设置","account",action);
                case "privacy_settings" -> nativeResult("可以在这里调整匿名分析同意设置。","管理隐私设置","privacy",action);
                case "erase_analytics" -> {
                    var erased=erasure.erase(owner);
                    boolean done=erased.status()==AnalyticsDataErasureService.Status.SUCCESS;
                    yield result(done?"已处理你的历史分析数据，共 "+erased.affected()+" 条。":"历史分析数据暂未完成处理，当前状态："+erased.status()+"。", "privacy", List.of(),List.of(),done?action:null);
                }
                default -> throw new DomainException("UNKNOWN_AGENT_ACTION","这个账号功能尚未接入。");
            };
        } catch(ResponseStatusException e) {
            throw new DomainException("AGENT_ACCOUNT_ACTION_FAILED",e.getReason()==null?"无法读取或操作该订单，请检查账号与订单状态。":e.getReason());
        }
    }
    private Result credits(String owner) {
        List<Item> items=List.of(new Item("balance","可用积分",credits.balance(owner)+" 积分",Map.of()),
            new Item("music","生成音乐",pricing.cost(CreditsPricing.Kind.MUSIC)+" 积分 / 次",Map.of()),
            new Item("lyrics","生成歌词",pricing.cost(CreditsPricing.Kind.LYRICS)+" 积分 / 次",Map.of()),
            new Item("rewrite","改写歌词",pricing.cost(CreditsPricing.Kind.REWRITE)+" 积分 / 次；免费改写剩余 "+rewrites.freeRemaining(owner)+" 次",Map.of()));
        return result("这是当前账号的积分与价格。", "credits",items,List.of(new Command("积分明细","查看我的积分明细","send"),new Command("充值","","recharge")),"get_credits");
    }
    private Result entries(String owner) {
        var items=db.query("SELECT entry_type,amount,created_at FROM credit_ledger WHERE owner_id=? ORDER BY created_at DESC,reference_id DESC LIMIT 30",
            (rs,n)->new Item("entry-"+n,switch(rs.getString(1)){case "CREDIT"->"积分入账";case "DEBIT"->"创作消费";case "REFUND"->"积分退回";default->"积分变动";},rs.getLong(2)+" 积分 · "+rs.getTimestamp(3).toInstant(),Map.of()),owner);
        return result(items.isEmpty()?"暂时没有积分收支记录。":"最近 30 条积分收支如下。","credits",items,List.of(),"list_credit_entries");
    }
    private Result usage(String owner) {
        List<Item> items=new ArrayList<>();
        for(var kind:UsageBudget.Kind.values()) {
            var value=usage.allowance(owner,kind);
            String name=switch(kind){case MUSIC->"音乐";case LYRICS->"歌词";case TIMING->"歌词时间轴";case COVER->"封面";};
            items.add(new Item(kind.name(),name,"今日剩余："+(value.dailyUnlimited()?"不限":value.dailyRemaining())+"；正在处理："+value.active(),Map.of()));
        }
        return result("这是当前账号的使用额度。","usage",items,List.of(),"get_usage");
    }
    private Result account(String owner) {
        var google=identity.googleIdentity(owner);
        Integer verified=db.queryForObject("SELECT (SELECT COUNT(*) FROM creator_google_identity WHERE creator_id=?) + (SELECT COUNT(*) FROM creator_oidc_identity WHERE creator_id=?)",Integer.class,owner,owner);
        boolean signedIn=verified!=null&&verified>0;
        String name=google.map(IdentityApi.GoogleIdentity::name).filter(v->v!=null&&!v.isBlank()).orElse(signedIn?"已登录账号":"访客账号");
        return result("当前账号状态如下。","account",List.of(new Item("account",name,signedIn?"已登录":"尚未登录",Map.of()),new Item("recovery","账号恢复",identity.hasRecoveryCode(owner)?"已配置恢复码":"尚未配置恢复码",Map.of())),List.of(new Command(signedIn?"账号设置":"登录账号","",signedIn?"account":"login")),"get_account");
    }
    @SuppressWarnings("unchecked") private Result orders(String owner) {
        List<Item> items=new ArrayList<>();List<Command> commands=new ArrayList<>();
        addOrders(items,commands,(List<Map<String,Object>>)payments.orders(new CreatorPrincipal(owner)).getOrDefault("orders",List.of()),"airwallex");
        addOrders(items,commands,(List<Map<String,Object>>)paypal.orders(owner).getOrDefault("orders",List.of()),"paypal");
        items.sort(Comparator.comparing((Item i)->String.valueOf(i.data().getOrDefault("createdAt",""))).reversed());
        return result(items.isEmpty()?"当前账号暂无充值订单。":"这是你的充值订单，点击核对可查询最新状态。","orders",items,commands,"list_orders");
    }
    private void addOrders(List<Item> items,List<Command> commands,List<Map<String,Object>> rows,String provider) {
        for(var row:rows) {
            String id=String.valueOf(row.get("orderId"));
            items.add(new Item(id,"订单 "+id,row.get("credits")+" 积分 · "+row.get("status"),Map.of("orderId",id,"provider",provider,"createdAt",String.valueOf(row.getOrDefault("createdAt","")))));
            commands.add(new Command("核对订单 "+id,"查询订单 "+id+"，渠道 "+provider,"send"));
        }
    }
    private Result order(String owner,Map<String,Object> args,boolean close) {
        String id=required(args,"orderId"), provider=String.valueOf(args.getOrDefault("provider","paypal"));
        if(!Set.of("paypal","airwallex").contains(provider)||close&&!"paypal".equals(provider))throw new DomainException("INVALID_PAYMENT_PROVIDER","请选择订单原来的支付渠道。");
        var view=close?paypal.close(owner,id):"paypal".equals(provider)?paypal.order(owner,id):payments.order(new CreatorPrincipal(owner),id);
        return result("订单状态已核对。","orders",List.of(new Item(id,"订单 "+id,view.get("credits")+" 积分 · "+view.get("status"),Map.of("orderId",id,"provider",provider))),List.of(new Command("打开收银台","","recharge")),close?"close_order":"get_order");
    }
    @SuppressWarnings("unchecked") private Result catalog() {
        List<Item> items=new ArrayList<>();
        for(var entry:Map.of("airwallex",payments.catalog(),"paypal",paypal.catalog()).entrySet()) {
            if(!Boolean.TRUE.equals(entry.getValue().get("enabled")))continue;
            for(var pack:(List<Map<String,Object>>)entry.getValue().getOrDefault("packages",List.of()))
                items.add(new Item(entry.getKey()+":"+pack.get("id"),String.valueOf(pack.getOrDefault("title",pack.get("id"))),pack.get("credits")+" 积分 · "+pack.get("amount")+" "+pack.get("currency"),Map.of("provider",entry.getKey())));
        }
        return result(items.isEmpty()?"当前暂无可购买的充值套餐。":"这是当前开放的充值套餐。","catalog",items,items.isEmpty()?List.of():List.of(new Command("选择套餐并支付","","recharge")),"payment_catalog");
    }
    private static String required(Map<String,Object> args,String key) {
        var value=args.get(key);if(value==null||value.toString().isBlank()||value.toString().length()>128)throw new DomainException("INVALID_AGENT_ARGUMENT","请提供明确的"+key+"。");return value.toString().trim();
    }
    private static Result nativeResult(String reply,String label,String kind,String action){return result(reply,"account",List.of(),List.of(new Command(label,"",kind)),action);}
    private static Result result(String reply,String kind,List<Item> items,List<Command> commands,String action){return new Result(reply,kind,items,commands,action);}
}
