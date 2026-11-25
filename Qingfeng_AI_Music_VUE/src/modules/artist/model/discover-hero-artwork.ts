import coast from '../../../assets/discover/responsive/coast-1536.webp'
import compactCoast from '../../../assets/discover/responsive/coast-960.webp'
import seaStacks from '../../../assets/discover/responsive/carousel-sea-stacks-1536.webp'
import compactSeaStacks from '../../../assets/discover/responsive/carousel-sea-stacks-960.webp'
import cactus from '../../../assets/discover/responsive/carousel-cactus-1536.webp'
import compactCactus from '../../../assets/discover/responsive/carousel-cactus-960.webp'
import airportGuitar from '../../../assets/discover/responsive/carousel-airport-guitar-1536.webp'
import compactAirportGuitar from '../../../assets/discover/responsive/carousel-airport-guitar-960.webp'
import countryside from '../../../assets/discover/responsive/carousel-countryside-1536.webp'
import compactCountryside from '../../../assets/discover/responsive/carousel-countryside-960.webp'
import mistyGarden from '../../../assets/discover/responsive/carousel-misty-garden-1536.webp'
import compactMistyGarden from '../../../assets/discover/responsive/carousel-misty-garden-960.webp'
import jixiangwuFallback from '../../../assets/discover/responsive/jixiangwu-hero-361167958fda-cover.jpg'
import prayerFallback from '../../../assets/discover/responsive/prayer-beneath-the-stars-hero-7761229f5dee-cover.jpg'
import discoverMedia from './discover-media.json'

export interface HeroArtwork {
  desktop: string
  compact: string
  position?: string
  fallback?: string
}

export const discoverIntroArtwork: HeroArtwork = { desktop: coast, compact: compactCoast }

const jixiangwuHero = discoverMedia['jixiangwu-hero'].cover
const prayerHero = discoverMedia['prayer-beneath-the-stars-hero'].cover
export const jixiangwuHeroArtwork: HeroArtwork = {
  desktop: jixiangwuHero, compact: jixiangwuHero, position: '70% center', fallback: jixiangwuFallback,
}
export const prayerHeroArtwork: HeroArtwork = {
  desktop: prayerHero, compact: prayerHero, position: '70% center', fallback: prayerFallback,
}

export const discoverSongArtwork = [
  { desktop: seaStacks, compact: compactSeaStacks, position: '58% center' },
  { desktop: cactus, compact: compactCactus, position: '65% top' },
  { desktop: airportGuitar, compact: compactAirportGuitar, position: '62% 32%' },
  { desktop: countryside, compact: compactCountryside, position: '15% center' },
  { desktop: mistyGarden, compact: compactMistyGarden, position: 'center 60%' },
] as const satisfies readonly HeroArtwork[]
