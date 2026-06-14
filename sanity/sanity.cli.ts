import {defineCliConfig} from 'sanity/cli'

// projectId 는 sanity.config.ts 와 동일하게. `sanity deploy` 가 이걸 참조.
// studioHost = 배포될 Studio 웹주소(<studioHost>.sanity.studio). 전역 유니크, 한 번 정하면 고정.
export default defineCliConfig({
  api: {projectId: 'rc448mwo', dataset: 'production'},
  studioHost: 'plopcool',
})
