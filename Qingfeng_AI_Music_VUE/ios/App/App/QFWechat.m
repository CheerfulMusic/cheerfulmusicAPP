#import "QFWechat.h"
#import <WechatOpenSDK/WXApi.h>
#import <WechatOpenSDK/WXApiObject.h>

@interface QFWechat () <WXApiDelegate>
@property (nonatomic, copy) NSString *openedSong;
@end

@implementation QFWechat

+ (instancetype)shared {
    static QFWechat *instance;
    static dispatch_once_t once;
    dispatch_once(&once, ^{ instance = [[self alloc] init]; });
    return instance;
}

+ (NSString *)appID {
    return [NSBundle.mainBundle objectForInfoDictionaryKey:@"WeChatMobileAppID"];
}

+ (BOOL)registerIfConfigured {
    NSString *appID = [self appID];
    if (appID.length != 18 || [appID rangeOfString:@"^wx[0-9a-fA-F]{16}$" options:NSRegularExpressionSearch].location == NSNotFound) return NO;
    return [WXApi registerApp:appID universalLink:@"https://cheerfulmusic.com/app/wechat/"];
}

+ (NSDictionary *)availability {
    BOOL configured = [self registerIfConfigured];
    BOOL installed = configured && [WXApi isWXAppInstalled];
    return @{@"configured": @(configured), @"installed": @(installed), @"supported": @(installed && [WXApi isWXAppSupportApi])};
}

+ (BOOL)validSong:(NSString *)value {
    return [value isKindOfClass:NSString.class] && value.length == 43 &&
        [value rangeOfString:@"^[A-Za-z0-9_-]{43}$" options:NSRegularExpressionSearch].location != NSNotFound;
}

+ (BOOL)validText:(NSString *)text limit:(NSUInteger)bytes {
    return [text isKindOfClass:NSString.class] && [text stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet].length > 0 &&
        [text lengthOfBytesUsingEncoding:NSUTF8StringEncoding] <= bytes;
}

+ (void)share:(NSDictionary *)payload completion:(void (^)(BOOL))completion {
    NSString *song = payload[@"songId"], *title = payload[@"title"], *singer = payload[@"singer"];
    NSString *channel = payload[@"destination"], *encoded = payload[@"thumbnailBase64"];
    NSNumber *duration = payload[@"durationMs"];
    BOOL timeline = [channel isEqual:@"timeline"];
    if (![self validSong:song] || ![self validText:title limit:512] || ![self validText:singer limit:1024] ||
        ![duration isKindOfClass:NSNumber.class] || duration.longLongValue < 1 || duration.longLongValue > INT32_MAX ||
        (!timeline && ![channel isEqual:@"friend"]) || ![self validText:encoded limit:43692] ||
        ![self registerIfConfigured] || ![WXApi isWXAppInstalled] || ![WXApi isWXAppSupportApi]) {
        completion(NO);
        return;
    }
    NSData *thumbnail = [[NSData alloc] initWithBase64EncodedString:encoded options:0];
    if (thumbnail.length == 0 || thumbnail.length > 32768) { completion(NO); return; }

    WXMusicVideoObject *track = [WXMusicVideoObject object];
    track.musicUrl = [@"https://cheerfulmusic.com/song/" stringByAppendingString:song];
    track.musicDataUrl = [NSString stringWithFormat:@"https://cheerfulmusic.com/api/songs/%@/audio", song];
    track.singerName = singer;
    track.duration = duration.unsignedIntValue;
    track.identification = song;
    if ([self validText:payload[@"lrc"] limit:32768]) track.songLyric = payload[@"lrc"];

    WXMediaMessage *media = [WXMediaMessage message];
    media.title = title;
    media.description = singer;
    media.thumbData = thumbnail;
    media.messageExt = song;
    media.mediaObject = track;
    SendMessageToWXReq *request = [SendMessageToWXReq new];
    request.bText = NO;
    request.scene = timeline ? WXSceneTimeline : WXSceneSession;
    request.message = media;
    [WXApi sendReq:request completion:completion];
}

+ (BOOL)handle:(NSURL *)url {
    if (![url.scheme isEqualToString:[self appID]] || ![self registerIfConfigured]) return NO;
    return [WXApi handleOpenURL:url delegate:self.shared];
}

+ (BOOL)continueActivity:(NSUserActivity *)activity {
    NSURL *url = activity.webpageURL;
    if (![url.scheme isEqualToString:@"https"] || ![url.host isEqualToString:@"cheerfulmusic.com"] ||
        ![url.path hasPrefix:@"/app/wechat/"] || ![self registerIfConfigured]) return NO;
    return [WXApi handleOpenUniversalLink:activity delegate:self.shared];
}

- (void)onReq:(BaseReq *)request {
    WXMediaMessage *message = nil;
    if ([request isKindOfClass:LaunchFromWXReq.class]) message = ((LaunchFromWXReq *)request).message;
    else if ([request isKindOfClass:ShowMessageFromWXReq.class]) message = ((ShowMessageFromWXReq *)request).message;
    if ([message.mediaObject isKindOfClass:WXMusicVideoObject.class]) {
        WXMusicVideoObject *track = (WXMusicVideoObject *)message.mediaObject;
        NSString *song = [QFWechat validSong:message.messageExt] ? message.messageExt : track.identification;
        if ([QFWechat validSong:song]) self.openedSong = song;
    }
}

+ (NSString *)consumeOpenedSong {
    NSString *song = self.shared.openedSong;
    self.shared.openedSong = nil;
    return song;
}

@end
