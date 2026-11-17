import { describe, it, expect } from 'vitest'
import { videoTimeline, videoCost, sceneLabel, type VideoPlan } from '../src/modules/works/model/music-video'
describe('full-song MV timeline', () => {
  it('creates six consecutive 30-second scenes for a three-minute song', () => {
    const shots = videoTimeline(180000)
    expect(shots.map(s => s.seconds)).toEqual([30, 30, 30, 30, 30, 30])
    expect(shots.map((_, i) => sceneLabel(i, shots.length))).toEqual(['故事开场', '人物与场景建立', '故事发展', '情绪冲突', '高潮', '结局与收束'])
  })
  it('rounds a partial second once and prices generated video only', () => {
    const plan: VideoPlan = { title: '歌', ratio: '16:9', resolution: '720P', durationMillis: 180100, shots: videoTimeline(180100) }
    plan.shots[0]!.imageSeconds = 10; plan.shots[0]!.imageId = 'owned-image'
    expect(videoCost(plan)).toEqual({ generatedSeconds: 171, imageSeconds: 10, credits: 342 })
    plan.resolution = '1080P'; expect(videoCost(plan).credits).toBe(513)
    plan.resolution = '480P'; expect(videoCost(plan).credits).toBe(171)
  })
  it('does not charge for a complete image slideshow', () => {
    const shots = videoTimeline(45000).map(s => ({ ...s, imageSeconds: s.seconds, imageId: 'image' }))
    expect(videoCost({ title: '歌', ratio: '16:9', resolution: '1080P', durationMillis: 45000, shots }).credits).toBe(0)
  })
})
