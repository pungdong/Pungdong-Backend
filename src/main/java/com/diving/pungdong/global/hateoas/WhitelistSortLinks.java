package com.diving.pungdong.global.hateoas;

import org.springframework.hateoas.Link;
import org.springframework.hateoas.PagedModel;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 페이지 링크에 <b>화이트리스트 정렬 파라미터를 되붙인다.</b>
 *
 * <p><b>왜 필요한가.</b> {@code PagedResourcesAssembler} 는 self/next/prev 링크를 만들 때 요청의
 * {@code sort} 파라미터를 <b>지우고</b> {@code Page} 가 들고 있는 {@code Sort} 를 다시 붙인다. 그런데
 * 이 레포의 둘러보기 엔드포인트들은 클라이언트 정렬을 태우지 않으려고 {@code PageClamp} 로 {@code Sort}
 * 를 버린다 — 그래서 붙일 게 없어 <b>{@code sort} 만 조용히 증발한다.</b> 나머지 파라미터
 * ({@code disciplineCode}·{@code keyword}·필터)는 그대로 남기 때문에 눈에 잘 띄지 않는다.
 *
 * <p><b>증상은 조용하고 나쁘다.</b> "강의 많은순" 으로 보다가 {@code _links.next} 를 따라가면 서버는
 * 기본 정렬로 2페이지를 계산한다 → <b>1페이지에 있던 항목이 또 나오고, 어떤 항목은 영영 도달하지
 * 못한다.</b> 에러가 아니라 잘못된 목록이라 테스트도 화면도 통과한다.
 *
 * <p>정렬 값은 enum 이름({@code A-Z_})이라 인코딩이 필요 없다. {@code sort} 가 없으면(기본 정렬이면)
 * 아무것도 하지 않는다 — 링크에 없으면 서버가 어차피 기본값을 쓴다.
 */
public final class WhitelistSortLinks {

    private WhitelistSortLinks() {
    }

    /**
     * {@code model} 이 이미 갖고 있는 링크 전부에 {@code sort=<enum 이름>} 을 덧붙인다.
     * <b>{@code profile} 같은 부가 링크를 add 하기 전에</b> 호출할 것(그 링크엔 붙일 이유가 없다).
     */
    public static void apply(PagedModel<?> model, Enum<?> sort) {
        if (sort == null) {
            return;
        }
        List<Link> rewritten = model.getLinks().stream()
                .map(link -> Link.of(withSortParam(link.getHref(), sort.name()), link.getRel()))
                .collect(Collectors.toList());
        model.removeLinks();
        model.add(rewritten);
    }

    private static String withSortParam(String href, String sortName) {
        if (href.contains("sort=")) {
            return href; // 이미 있으면 건드리지 않는다(중복 파라미터는 바인딩을 망가뜨린다)
        }
        return href + (href.contains("?") ? "&" : "?") + "sort=" + sortName;
    }
}
