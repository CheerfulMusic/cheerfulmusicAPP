import type { PlayableTrack } from '../../../shared/player/store'
import discoverMedia from './discover-media.json'
import sixEaredDebate from '../../../assets/discover/editorial/cover-17.webp'
import awaitingDawn from '../../../assets/discover/editorial/cover-05.webp'
import whenCitySleeps from '../../../assets/discover/song-covers/when-city-sleeps.webp'
import jixiangwu from '../../../assets/discover/song-covers/jixiangwu.webp'
import lookWhatYouDoToMe from '../../../assets/discover/song-covers/look-what-you-do-to-me.webp'
import twoAmLosAngeles from '../../../assets/discover/editorial/cover-29.webp'
import somethingAboutYou from '../../../assets/discover/editorial/cover-07.webp'
import crossroads from '../../../assets/discover/editorial/cover-06.webp'
import wanFengCheZhan from '../../../assets/discover/song-covers/wan-feng-che-zhan.webp'
import replyFromTheWind from '../../../assets/discover/editorial/cover-09.webp'
import neonWasteland from '../../../assets/discover/editorial/cover-10.webp'
import brokenWindow from '../../../assets/discover/editorial/cover-03.webp'
import milkAndGoodnight from '../../../assets/discover/editorial/cover-16.webp'
import skinDeep from '../../../assets/discover/editorial/cover-13.webp'
import amysShadow from '../../../assets/discover/editorial/cover-31.webp'
import windowAtNight from '../../../assets/discover/song-covers/window-at-night.webp'
import engineOilSnow from '../../../assets/discover/song-covers/engine-oil-snow.webp'
import umbrellaStarlight from '../../../assets/discover/editorial/cover-12.webp'
import bowlInWater from '../../../assets/discover/editorial/cover-02.webp'
import npcAwakening from '../../../assets/discover/editorial/cover-32.webp'
import acrossTheWater from '../../../assets/discover/song-covers/across-the-water.webp'
import afterItsOkay from '../../../assets/discover/editorial/cover-34.webp'
import ordinaryEvening from '../../../assets/discover/editorial/cover-25.webp'
import freezeThisMoment from '../../../assets/discover/song-covers/freeze-this-moment.webp'
import sunDriedWorries from '../../../assets/discover/editorial/cover-01.webp'
import midnight from '../../../assets/discover/song-covers/midnight.webp'
import saltAndStatic from '../../../assets/discover/editorial/cover-19.webp'
import letTheRhythm from '../../../assets/discover/editorial/cover-20.webp'
import likeSecondHeart from '../../../assets/discover/editorial/cover-21.webp'
import dropItLow from '../../../assets/discover/editorial/cover-22.webp'
import gravity from '../../../assets/discover/editorial/cover-23.webp'
import staticBloom from '../../../assets/discover/editorial/cover-24.webp'
import onlyRoute from '../../../assets/discover/responsive/carousel-sea-stacks-960.webp'
import oldMansTerritory from '../../../assets/discover/song-covers/old-mans-territory.webp'
import micangshan from '../../../assets/discover/editorial/cover-18.webp'
import invincibleSong from '../../../assets/discover/editorial/cover-27.webp'
import rainThroughYourStreet from '../../../assets/discover/song-covers/rain-through-your-street.webp'
import streetlight from '../../../assets/discover/editorial/cover-14.webp'
import jiuXiangDeng from '../../../assets/discover/song-covers/jiu-xiang-deng.webp'
import twoYuan from '../../../assets/discover/editorial/cover-36.webp'
import poetSong from '../../../assets/discover/editorial/cover-28.webp'
import loveTheWorld from '../../../assets/discover/editorial/cover-26.webp'
import nextWave from '../../../assets/discover/editorial/cover-33.webp'
import airTastesSweet from '../../../assets/discover/editorial/cover-30.webp'
import ninetyNine from '../../../assets/discover/editorial/cover-35.webp'
import rainDisco from '../../../assets/discover/song-covers/rain-disco.webp'
import tomorrowRock from '../../../assets/discover/editorial/cover-08.webp'
import hometownElectronic from '../../../assets/discover/song-covers/hometown-electronic.webp'
import dreamWings from '../../../assets/discover/song-covers/dream-wings.webp'
import bodyLanguage from '../../../assets/discover/song-covers/body-language.webp'
import letTheLightTakeItsTime from '../../../assets/discover/song-covers/let-the-light-take-its-time.webp'

// Editorial shelves share song-specific artwork even when a song changes rank.
const coversBySong = new Map<string, string>([
  ['5ea96e39-113b-4ae9-9579-19d85dc4b063', sixEaredDebate],
  ['be7e4adc-e671-45fb-afd0-b87f1ab915bc', awaitingDawn],
  ['375e3bb7-c6fb-460f-916e-5e86442c7d1c', whenCitySleeps],
  ['1485ddee-9a1f-435b-bfc8-403cccb8c243', jixiangwu],
  ['9e42f15b-d819-48d8-bb51-1920ba981692', lookWhatYouDoToMe],
  ['24bdb016-c5b5-471d-b25d-a53f0bed489c', twoAmLosAngeles],
  ['68d6506a-194d-4f81-be6a-94404b258d7f', somethingAboutYou],
  ['0a67006e-997d-49b1-a162-a1a02f424c91', crossroads],
  ['21282ee4-0645-4a38-aa08-3ba3b79be197', wanFengCheZhan],
  ['1858dcd2-489e-49ad-ae7a-4627f5d64403', replyFromTheWind],
  ['2db29b51-9778-41ab-afce-a06b8fd9ede4', neonWasteland],
  ['416b2556-2df2-4177-95a4-7c96da274457', brokenWindow],
  ['469e3e8b-1e4a-453b-8353-72f8cbb17f2c', milkAndGoodnight],
  ['0c223cc9-4b8b-4fe1-a1f8-a0062e847daa', skinDeep],
  ['d3e1fff9-c7a6-4171-9a5c-9649cb82ff73', amysShadow],
  ['752fa2d9-ec96-47c3-87a5-a1d49d0135af', discoverMedia['snow-on-the-floor'].cover],
  ['8ffd88c1-7f05-4867-abe8-74bfbdb9a2cb', windowAtNight],
  ['4d54b21d-90fc-4404-866c-7b0a0bc201c4', engineOilSnow],
  ['af021d33-1dff-48b9-bff6-41643f1bb58f', umbrellaStarlight],
  ['f3704e73-1a8a-4455-a0c6-385ba70f499d', bowlInWater],
  ['8492052a-de13-49ec-9fea-aceae778cd41', npcAwakening],
  ['7b5fa49b-1b1f-47db-ac0e-15f7a9de5ea6', acrossTheWater],
  ['9344e244-c27b-4b8d-af4c-fac637584740', afterItsOkay],
  ['998346d4-6955-474a-8b98-25e702a8dd58', ordinaryEvening],
  ['37ae6369-b938-431a-a9be-0fd73927e6c0', freezeThisMoment],
  ['1cc6d5cf-7cb3-4dd0-a428-01c290fce1ed', sunDriedWorries],
  ['db6c9d32-b3b2-465f-9ba1-3edfee62d4d3', midnight],
  ['981fa284-6e73-4feb-905f-ce74a86d2aab', saltAndStatic],
  ['d29a567d-988a-44fe-b5d8-c15ab625a23a', letTheRhythm],
  ['ce24a243-d3c3-4d8e-942c-d7d3a9c0170d', likeSecondHeart],
  ['ab7125fe-b8d4-4b85-8531-1f840d0a2997', dropItLow],
  ['0485811c-0297-4513-9fb1-75f981af8707', gravity],
  ['45e28036-c2bc-4f44-886c-3e40ddb32bf0', staticBloom],
  ['de4668db-cddf-4f7f-b66a-a0f5a4f54c79', onlyRoute],
  ['acfaa9d5-ce74-477f-9db8-d3700311a26c', oldMansTerritory],
  ['09aaf684-2b68-438f-aa17-8730a352bf74', micangshan],
  ['57bb7310-c64c-4873-b239-f2a268a7dedd', invincibleSong],
  ['bf6812f2-56dd-4359-8e50-a2dc5a990bc7', rainThroughYourStreet],
  ['0f831f4b-1f3f-4d14-b6cf-b33534863622', streetlight],
  ['9d1f9ba6-af67-4844-addc-5e578b579328', jiuXiangDeng],
  ['f44d6e1a-2df8-4978-ab2a-d9e1790b2436', twoYuan],
  ['173e83f4-8aba-4238-8e8d-aead06310c0d', poetSong],
  ['8335c241-533e-4b84-a26e-9cd7cd05f446', loveTheWorld],
  ['aac14834-1b00-4016-936b-576fb73fb651', nextWave],
  ['cb0df61f-7814-440e-991e-fb29ab9905a2', airTastesSweet],
  ['6ce51e3c-7ff6-485d-9f82-bacb1a89e0c6', ninetyNine],
  ['13165085-71ba-4bf9-bece-a5f0cc59d4b5', letTheLightTakeItsTime],
  ['1c5c325c-6a45-4607-a8e4-5baadabc2032', discoverMedia.navidad.cover],
  ['rain-disco', rainDisco],
  ['tomorrow-rock', tomorrowRock],
  ['hometown-electronic', hometownElectronic],
  ['meng-de-chi-bang', dreamWings],
  ['body-language', bodyLanguage],
])

export function discoverEditorialCover(songId: string) {
  return coversBySong.get(songId)
}

export function withDiscoverEditorialCover<T extends PlayableTrack>(track: T): T {
  const cover = discoverEditorialCover(track.id)
  return cover ? { ...track, cover } : track
}
