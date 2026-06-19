#!/usr/bin/env python3
"""
공모전 데모 시드 — 강사 5명 + 강의 5개 + 투어 5개를 실 REST API 로 생성한다.

전제:
  - 앱이 localhost:8080 에서 구동 중 (최신 master, 데모 이미지 static 반영을 위해 재시작된 상태).
  - docker MySQL(pungdong) 가 떠 있음 (초기화 SQL 용).
  - 이미지가 src/main/resources/static/images/demo/ 에 번들됨 → /images/demo/<slug>-<n>.jpg 로 서빙.

흐름(강사 1명당): 회원가입 → 본인확인(stub 즉시 VERIFIED) → 강사신청(SUBMITTED, 그 종목)
              → 커스텀 위치 생성(풀=강의, 해양=투어) → 코스 생성 → 상태 OPEN.
강사 role 은 STUDENT 로 남지만(정식 승인 생략) 둘러보기 카드는 nickName 으로 강사명을 표시하므로 데모엔 영향 없음.

재실행 = 깨끗한 초기화: 모든 course/venue/application/identity 행을 비우고 demo_* 계정을 지운 뒤 새로 만든다.
"""
import json
import subprocess
import sys
import urllib.request
import urllib.error
from pathlib import Path

BASE = "http://localhost:8080"
PASSWORD = "Pungdong!23"

# 이미지는 Sanity 에셋(공개 CDN URL) 사용 — FE 가 어느 origin 에서 띄워도 보이게.
# 맵은 scripts/upload_demo_to_sanity.py 가 생성. (없으면 먼저 그 스크립트를 실행할 것.)
_ASSETS_PATH = Path(__file__).resolve().parent / "demo_sanity_assets.json"
ASSETS = json.loads(_ASSETS_PATH.read_text()) if _ASSETS_PATH.exists() else {}
if not ASSETS:
    raise SystemExit("Sanity 에셋 맵 없음 — 먼저 `python3 scripts/upload_demo_to_sanity.py` 실행")

# 자격증 이미지(application fileURL)는 문자열로만 저장됨 — 에셋 하나 재사용.
CERT_IMG = ASSETS["lecture-1-1.jpg"]


def http(method, path, token=None, body=None):
    url = BASE + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    req.add_header("Accept", "application/hal+json, application/json, */*")
    if token:
        req.add_header("Authorization", token)  # raw JWT, no Bearer prefix
    try:
        with urllib.request.urlopen(req) as r:
            raw = r.read().decode()
            return r.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw}


def media_for(slug):
    # 파일명은 (커밋된) 에셋 맵에서 — 원본 이미지 파일이 없어도 재현 가능.
    names = sorted((n for n in ASSETS if n.startswith(slug + "-")),
                   key=lambda n: int(n.rsplit("-", 1)[1].split(".")[0]))
    return [{"kind": "PHOTO", "url": ASSETS[n]} for n in names]


def wipe_db():
    tables = [
        # course graph
        "round_venue_ticket", "round_venue", "course_round", "course_media",
        "course_level", "course_region", "course",
        # venue graph
        "venue_ticket_discipline", "venue_ticket", "venue_daypart", "venue_time_block",
        "venue_closure_weekday", "venue_closure_nth", "venue_closure", "venue_media",
        "venue_equipment_item_size", "venue_equipment_item", "venue_equipment_extension",
        "venue_equipment_profile", "venue",
        # instructor track
        "application_certificate", "instructor_application", "identity_verification",
    ]
    stmts = ["SET FOREIGN_KEY_CHECKS=0;"]
    stmts += [f"DELETE FROM {t};" for t in tables]
    stmts.append("DELETE FROM account WHERE email LIKE 'demo\\_%';")
    stmts.append("SET FOREIGN_KEY_CHECKS=1;")
    sql = " ".join(stmts)
    res = subprocess.run(
        ["docker", "exec", "-i", "pungdong-mysql",
         "mysql", "-upungdong", "-ppungdongpw", "pungdong", "-e", sql],
        capture_output=True, text=True,
    )
    if res.returncode != 0:
        print("  ! wipe 경고:", res.stderr.strip()[:300])
    else:
        print("  · 기존 course/venue/application/identity + demo 계정 초기화 완료")


def signup_or_login(email, nick):
    s, b = http("POST", "/sign/sign-up", body={"email": email, "password": PASSWORD, "nickName": nick})
    if s in (200, 201):
        return b["tokens"]["access_token"]
    # 이미 존재 → 로그인
    s, b = http("POST", "/sign/login", body={"email": email, "password": PASSWORD})
    if s == 200:
        return b["access_token"]
    raise RuntimeError(f"auth 실패 {email}: {s} {b}")


def verify_identity(token, name):
    s, b = http("POST", "/identity-verifications", token, {
        "realName": name, "birth": "19900101", "gender": "MALE",
        "phoneNumber": "01012345678", "provider": "KAKAO", "agreedRequiredTerms": True,
    })
    if s not in (200, 201):
        raise RuntimeError(f"본인확인 실패: {s} {b}")
    return b["verificationId"]


def apply_instructor(token, discipline, verification_id, org, org_other=None):
    cert = {"organizationCode": org, "fileURL": CERT_IMG}
    if org == "OTHER":
        cert["organizationOther"] = org_other or "기타 협회"
    s, b = http("POST", "/instructor-applications", token, {
        "disciplineCode": discipline,
        "verificationId": verification_id,
        "certificates": [cert],
    })
    if s not in (200, 201):
        raise RuntimeError(f"강사신청 실패: {s} {b}")
    return b["applicationId"]


def create_venue(token, discipline, vtype, name, address, lat, lng, depth, fee_wd, fee_we):
    body = {
        "name": name, "type": vtype, "address": address, "addressDetail": "",
        "latitude": lat, "longitude": lng, "maxDepth": depth,
        "lockedDisciplineCode": discipline,
        "tickets": [{
            "name": "다이빙 1회 이용권 (2시간)", "sortOrder": 0,
            "disciplineCodes": [discipline],
            "dayparts": [
                {"kind": "WEEKDAY", "sold": True, "fee": fee_wd, "timeMode": "OPEN",
                 "openStart": "09:00:00", "openEnd": "21:00:00", "holdHours": 2},
                {"kind": "WEEKEND", "sold": True, "fee": fee_we, "timeMode": "OPEN",
                 "openStart": "08:00:00", "openEnd": "20:00:00", "holdHours": 2},
            ],
        }],
    }
    s, b = http("POST", "/venues", token, body)
    if s not in (200, 201):
        raise RuntimeError(f"위치 생성 실패({name}): {s} {b}")
    return b["venueRefId"], b["tickets"][0]["ticketRef"]


def create_course(token, course, venue_ref, ticket_ref):
    rounds = []
    for r in course["rounds"]:
        rounds.append({
            "description": r,
            "venues": [{"venueRefId": venue_ref,
                        "tickets": [{"ticketRef": ticket_ref, "daypart": "WEEKDAY"}]}],
        })
    body = {
        "title": course["title"],
        "kind": course["kind"],
        "disciplineCode": course["discipline"],
        "totalRounds": len(rounds),
        "price": course["price"],
        "description": course["description"],
        "media": media_for(course["slug"]),
        "rounds": rounds,
    }
    if course["kind"] == "CERTIFICATION":
        body["organizationCode"] = course["org"]
        body["levels"] = course["levels"]
    s, b = http("POST", "/courses", token, body)
    if s not in (200, 201):
        raise RuntimeError(f"코스 생성 실패({course['title']}): {s} {b}")
    cid = b["id"]
    s, b = http("PATCH", f"/courses/{cid}/status", token, {"status": "OPEN"})
    if s != 200:
        raise RuntimeError(f"OPEN 전환 실패({course['title']}): {s} {b}")
    return cid


# ───────────────────────── 데이터 정의 ─────────────────────────
INSTRUCTORS = [
    {
        "email": "demo_inst1@plop.cool", "nick": "김도현", "discipline": "FREEDIVING", "org": "AIDA",
        "lecture": {
            "slug": "lecture-1", "title": "프리다이빙 입문 체험 클래스", "kind": "TRIAL", "price": 89000,
            "rounds": ["수심 5m 풀에서 첫 호흡 정지와 이퀄라이징을 배우는 1:1 입문 세션입니다."],
            "description": "숨을 참고 물속으로 내려가는 첫 경험. 호흡법·이퀄라이징·안전 수칙을 1:1로 차근차근 익히는 입문 체험 클래스입니다. 장비 없이 수영만 가능하면 누구나 참여할 수 있어요.",
        },
        "tour": {
            "slug": "tour-1", "title": "부산 오륙도 프리다이빙 투어", "kind": "TRAINING", "price": 150000,
            "rounds": ["오륙도 인근 블루워터에서 라인 다이빙과 수중 사진을 즐기는 당일 투어."],
            "description": "부산 오륙도 앞바다의 맑은 블루워터에서 즐기는 프리다이빙 투어. 라인 다이빙과 수중 사진 촬영을 함께 진행하며, 보트 이동·기본 장비·안전 다이버가 포함됩니다.",
        },
        "pool": ("부산 프리다이빙 센터", "부산광역시 해운대구 센텀중앙로 90", 35.169, 129.130, 7, 35000, 45000),
        "ocean": ("오륙도 다이빙 포인트", "부산광역시 남구 오륙도로 137", 35.094, 129.122, 18, 60000, 70000),
    },
    {
        "email": "demo_inst2@plop.cool", "nick": "이서윤", "discipline": "SCUBA", "org": "PADI",
        "lecture": {
            "slug": "lecture-2", "title": "PADI 오픈워터 다이버 자격 과정", "kind": "CERTIFICATION",
            "org": "PADI", "levels": ["LEVEL_1"], "price": 450000,
            "rounds": [
                "이론 교육과 장비 셋업 — 다이빙의 원리와 안전을 배웁니다.",
                "제한수역(풀) 실습 — 호흡·중성부력·마스크 클리어링.",
                "해양 실습 1 — 첫 바다 다이빙으로 기술을 적용합니다.",
                "해양 실습 2 — 자격 기준 완수 및 인증.",
            ],
            "description": "전 세계에서 통용되는 PADI 오픈워터 다이버 자격을 취득하는 정규 과정입니다. 이론·제한수역·해양 실습을 거쳐 최대 18m까지 자유롭게 다이빙할 수 있는 자격을 드립니다.",
        },
        "tour": {
            "slug": "tour-2", "title": "제주 문섬 스쿠버 투어", "kind": "TRAINING", "price": 220000,
            "rounds": [
                "문섬 새끼섬 포인트 — 연산호 군락과 열대어를 만나는 첫 다이브.",
                "문섬 한개창 포인트 — 수중 절벽과 대형 어군 탐사.",
            ],
            "description": "국내 최고의 다이빙 명소 제주 문섬에서 즐기는 2다이브 투어. 형형색색의 연산호 군락과 열대어, 수중 절벽을 만날 수 있습니다. 보트·웨이트·탱크·가이드 포함.",
        },
        "pool": ("서울 스쿠버 트레이닝풀", "서울특별시 송파구 올림픽로 424", 37.520, 127.121, 5, 30000, 40000),
        "ocean": ("문섬 다이빙 포인트", "제주특별자치도 서귀포시 남성중로 40", 33.227, 126.567, 30, 70000, 80000),
    },
    {
        "email": "demo_inst3@plop.cool", "nick": "박준영", "discipline": "SCUBA", "org": "SSI",
        "lecture": {
            "slug": "lecture-3", "title": "SSI 어드밴스드 어드벤처 다이버", "kind": "CERTIFICATION",
            "org": "SSI", "levels": ["LEVEL_2"], "price": 520000,
            "rounds": [
                "딥 다이빙 스페셜티 — 18~30m 수심 적응과 안전 정지.",
                "수중 내비게이션 — 컴퍼스와 자연 지형으로 길찾기.",
                "보트 다이빙 어드벤처 — 실전 보트 다이빙 완수 및 인증.",
            ],
            "description": "오픈워터 이후 한 단계 더 깊은 바다로. 딥 다이빙·수중 내비게이션·보트 다이빙을 통해 30m 수심까지 안전하게 즐기는 어드밴스드 자격 과정입니다.",
        },
        "tour": {
            "slug": "tour-3", "title": "강원 고성 해양 다이빙 투어", "kind": "TRAINING", "price": 180000,
            "rounds": ["고성 앞바다 난파선·암초 포인트에서 진행하는 어드벤처 다이브."],
            "description": "동해의 시원하고 맑은 바다, 강원 고성에서 즐기는 다이빙 투어. 난파선과 암초 지형, 동해 특유의 대형 어군을 만날 수 있는 어드벤처 코스입니다.",
        },
        "pool": ("일산 다이빙풀", "경기도 고양시 일산동구 중앙로 1275", 37.658, 126.770, 6, 30000, 40000),
        "ocean": ("고성 자작도 포인트", "강원특별자치도 고성군 죽왕면 가진리 1", 38.288, 128.556, 28, 65000, 75000),
    },
    {
        "email": "demo_inst4@plop.cool", "nick": "최예린", "discipline": "FREEDIVING", "org": "AIDA",
        "lecture": {
            "slug": "lecture-4", "title": "AIDA 레벨2 프리다이버 자격 과정", "kind": "CERTIFICATION",
            "org": "AIDA", "levels": ["LEVEL_2"], "price": 380000,
            "rounds": [
                "이론과 호흡 — 프리다이빙 생리학과 안전, 호흡 테크닉.",
                "풀 세션 — 스태틱·다이내믹 기록과 레스큐 훈련.",
                "해양 세션 — 수심 20m 라인 다이빙 인증.",
            ],
            "description": "AIDA 국제 레벨2 자격 과정. 호흡 생리학과 안전·레스큐를 익히고, 수심 20m 라인 다이빙과 스태틱·다이내믹 기준을 달성합니다. 입문을 마친 분께 추천합니다.",
        },
        "tour": {
            "slug": "tour-4", "title": "제주 범섬 블루홀 프리다이빙 투어", "kind": "TRAINING", "price": 240000,
            "rounds": [
                "범섬 블루홀 — 깊고 투명한 블루워터에서의 라인 다이빙.",
                "범섬 수중동굴 — 빛이 쏟아지는 수중 지형 탐사와 촬영.",
            ],
            "description": "제주 범섬의 상징 블루홀에서 즐기는 프리다이빙 투어. 투명도 높은 블루워터에서 깊이감을 만끽하고, 빛이 쏟아지는 수중동굴에서 인생샷을 남겨보세요.",
        },
        "pool": ("강남 프리다이빙 아카데미", "서울특별시 강남구 테헤란로 152", 37.500, 127.036, 8, 35000, 45000),
        "ocean": ("범섬 블루홀 포인트", "제주특별자치도 서귀포시 법환동 산1", 33.224, 126.524, 35, 75000, 85000),
    },
    {
        "email": "demo_inst5@plop.cool", "nick": "한지수", "discipline": "MERMAID", "org": "OTHER",
        "org_other": "대한머메이드협회",
        "lecture": {
            "slug": "lecture-5", "title": "머메이드 퍼포먼스 베이직 클래스", "kind": "TRIAL", "price": 120000,
            "rounds": [
                "모노핀 착용과 돌핀킥 — 인어 영법의 기본기를 배웁니다.",
                "수중 포즈와 촬영 — 우아한 인어 퍼포먼스와 사진 촬영.",
            ],
            "description": "모노핀을 신고 인어가 되어보는 머메이드 클래스. 돌핀킥과 수중 포즈를 배우고 전문 작가의 수중 촬영으로 환상적인 인어 사진을 남깁니다. 수영 가능자면 누구나 OK.",
        },
        "tour": {
            "slug": "tour-5", "title": "강원 양양 인어 체험 투어", "kind": "TRAINING", "price": 160000,
            "rounds": ["양양 맑은 바다에서 진행하는 머메이드 수중 촬영 투어."],
            "description": "동해 양양의 맑은 바다를 배경으로 한 머메이드 투어. 자연광이 아름다운 얕은 해양 포인트에서 인어 퍼포먼스와 수중 화보 촬영을 진행합니다. 모노핀·실루엣 의상 대여 포함.",
        },
        "pool": ("용인 머메이드 스튜디오풀", "경기도 용인시 기흥구 흥덕중앙로 120", 37.275, 127.115, 5, 35000, 45000),
        "ocean": ("양양 인구해변 포인트", "강원특별자치도 양양군 현남면 인구길 1", 37.971, 128.780, 12, 60000, 70000),
    },
    {
        # 6번째 강사 — 강의만(투어 없음). 루트 직속 사진 9장(인물 3 + 빅애니멀/블루홀 풍경 6)으로 구성.
        "email": "demo_inst6@plop.cool", "nick": "정우진", "discipline": "FREEDIVING", "org": "AIDA",
        "lecture": {
            "slug": "lecture-6", "title": "AIDA 레벨3 딥 프리다이버 자격 과정", "kind": "CERTIFICATION",
            "org": "AIDA", "levels": ["LEVEL_3"], "price": 620000,
            "rounds": [
                "심화 이론 — 마우스필·역압평형(프렌젤/마우스필)과 깊은 수심의 생리학.",
                "딥풀 세션 — FRC 다이빙과 깊은 수심 적응, 레스큐 심화.",
                "해양 세션 1 — 수심 30m 라인 다이빙과 프리폴.",
                "해양 세션 2 — 빅블루 환경 적응 및 자격 인증.",
            ],
            "description": "수심 30m 이상을 향하는 심화 프리다이빙 자격 과정. 마우스필 역압평형, 프리폴, FRC 다이빙을 익히고 귀상어 떼·정어리 베이트볼이 펼쳐지는 빅블루 환경에서 깊이감을 완성합니다. 레벨2 이수자 대상.",
        },
        "tour": None,
        "pool": ("K26 딥다이빙풀", "경기도 가평군 청평면 호반로 26", 37.731, 127.426, 26, 45000, 55000),
    },
]


def main():
    print(f"# 데모 시드 시작 — {BASE}\n[1/3] DB 초기화")
    wipe_db()

    print("[2/3] 강사·강의·투어 생성")
    created = {"instructors": [], "lectures": [], "tours": []}
    for i, inst in enumerate(INSTRUCTORS, 1):
        d = inst["discipline"]
        token = signup_or_login(inst["email"], inst["nick"])
        vid = verify_identity(token, inst["nick"])
        apply_instructor(token, d, vid, inst["org"], inst.get("org_other"))
        print(f"  강사{i} {inst['nick']} ({d}) — 가입·본인확인·신청 완료")

        inst["lecture"]["discipline"] = d

        # 강의용 풀 위치 + 코스
        p = inst["pool"]
        vref, tref = create_venue(token, d, "DIVING_POOL", *p)
        lid = create_course(token, inst["lecture"], vref, tref)
        print(f"    ↳ 강의 #{lid}  {inst['lecture']['title']}")
        created["instructors"].append(inst["nick"])
        created["lectures"].append((lid, inst["lecture"]["title"]))

        # 투어용 해양 위치 + 코스 (투어 없는 강사는 생략)
        if inst.get("tour"):
            inst["tour"]["discipline"] = d
            o = inst["ocean"]
            vref, tref = create_venue(token, d, "OCEAN", *o)
            tid = create_course(token, inst["tour"], vref, tref)
            print(f"    ↳ 투어 #{tid}  {inst['tour']['title']}")
            created["tours"].append((tid, inst["tour"]["title"]))

    print("[3/3] 검증 — 둘러보기 카운트")
    for d in ("FREEDIVING", "SCUBA", "MERMAID"):
        s, b = http("GET", f"/courses/browse?disciplineCode={d}&size=50")
        total = b.get("page", {}).get("totalElements", "?")
        print(f"  {d}: {total}개 OPEN")

    print(f"\n완료 — 강사 {len(created['instructors'])} · 강의 {len(created['lectures'])} · 투어 {len(created['tours'])}")


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print("실패:", e, file=sys.stderr)
        sys.exit(1)
