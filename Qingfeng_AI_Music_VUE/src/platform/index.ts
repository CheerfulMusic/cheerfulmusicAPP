import { Capacitor } from '@capacitor/core'
import { webPlatform } from './web'
import { createNativePlatform } from './native'

const nativeKind = Capacitor.getPlatform() === 'ios' ? 'ios' : 'android'
export const platform = Capacitor.isNativePlatform() ? createNativePlatform(nativeKind) : webPlatform
