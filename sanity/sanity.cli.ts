import {defineCliConfig} from 'sanity/cli'

// projectId 는 sanity.config.ts 와 동일하게. `sanity deploy` 가 이걸 참조.
export default defineCliConfig({
  api: {projectId: 'rc448mwo', dataset: 'production'},
})
