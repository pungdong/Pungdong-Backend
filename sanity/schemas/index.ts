import {certOrganization, certification} from './certOrganization'
import {term} from './term'
import {legalDocument} from './legalDocument'
import {siteSettings} from './siteSettings'
import {venue, venueTicket, venueDaypart, venueTimeBlock, venueClosure} from './venue'

/** Sanity Studio schema.types 에 넣을 문서/오브젝트 타입들. */
export const schemaTypes = [
  certOrganization,
  certification,
  term,
  legalDocument,
  siteSettings,
  venue,
  venueTicket,
  venueDaypart,
  venueTimeBlock,
  venueClosure,
]
