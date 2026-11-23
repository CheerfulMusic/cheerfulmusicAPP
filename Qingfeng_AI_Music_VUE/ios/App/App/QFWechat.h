#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN
@interface QFWechat : NSObject
+ (NSDictionary<NSString *, NSNumber *> *)availability;
+ (void)share:(NSDictionary<NSString *, id> *)payload completion:(void (^)(BOOL))completion NS_SWIFT_NAME(share(_:completion:));
+ (BOOL)handle:(NSURL *)url NS_SWIFT_NAME(handle(_:));
+ (BOOL)continueActivity:(NSUserActivity *)activity NS_SWIFT_NAME(continueActivity(_:));
+ (nullable NSString *)consumeOpenedSong;
@end
NS_ASSUME_NONNULL_END
