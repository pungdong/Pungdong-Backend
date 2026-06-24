import {certOrganization, certification} from './certOrganization'
import {term} from './term'
import {siteSettings} from './siteSettings'
import {venue, venueTicket, venueDaypart, venueTimeBlock, venueClosure} from './venue'

/** Sanity Studio schema.types 에 넣을 문서/오브젝트 타입들. */
export const schemaTypes = [
  certOrganization,
  certification,
  term,
  siteSettings,
  venue,
  venueTicket,
  venueDaypart,
  venueTimeBlock,
  venueClosure,
]
