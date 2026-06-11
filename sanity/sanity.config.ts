import {defineConfig} from 'sanity'
import {structureTool} from 'sanity/structure'
import {visionTool} from '@sanity/vision'
import {schemaTypes} from './schemas'

// ⚠️ sanity.io 에서 프로젝트 만들고 projectId 채우기 (dataset 은 보통 production).
//    env 로 빼도 됨: process.env.SANITY_STUDIO_PROJECT_ID
export default defineConfig({
  name: 'pungdong',
  title: '풍덩 CMS',
  projectId: 'rc448mwo',
  dataset: 'production',
  plugins: [structureTool(), visionTool()],
  schema: {types: schemaTypes},
})
