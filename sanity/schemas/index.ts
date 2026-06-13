import {certOrganization, certification} from './certOrganization'
import {term} from './term'
import {venue, venueTicket, venueDaypart, venueTimeBlock, venueClosure} from './venue'

/** Sanity Studio schema.types 에 넣을 문서/오브젝트 타입들. */
export const schemaTypes = [
  certOrganization,
  certification,
  term,
  venue,
  venueTicket,
  venueDaypart,
  venueTimeBlock,
  venueClosure,
]
