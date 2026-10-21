import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { categoryService } from '@/services/category.service';
import { departmentService } from '@/services/department.service';
import { wardService } from '@/services/ward.service';
import { queryKeys } from '@/lib/queryKeys';
import { humanizeEnum } from '@/lib/format';

/**
 * Reference data every Explorer view labels things with. Fetched once and
 * cached for as long as it plausibly stays the same, so moving between views
 * never refetches a vocabulary.
 *
 * Category semantics stay on the server: the code is the
 * identity, the registry's name is the label, and nothing here derives one
 * from the other beyond humanising a code the registry does not know.
 */
export function useCategoryRegistry() {
    const query = useQuery({
        queryKey: queryKeys.categories.list(),
        queryFn: () => categoryService.list(),
        staleTime: 60 * 60_000,
    });

    const label = useMemo(() => {
        const names = new Map((query.data ?? []).map((c) => [c.code, c.name]));
        return (code: string) => names.get(code) ?? humanizeEnum(code);
    }, [query.data]);

    /** Codes in registry order — the order every category list uses. */
    const codes = useMemo(() => (query.data ?? []).map((c) => c.code), [query.data]);

    return { categories: query.data ?? [], codes, label, isPending: query.isPending };
}

export function useWards() {
    return useQuery({
        queryKey: queryKeys.wards.list(),
        queryFn: () => wardService.list(),
        staleTime: 5 * 60_000,
    });
}

/** Department id -> name. Null ids render as "Unassigned" at the call site. */
export function useDepartmentNames() {
    const query = useQuery({
        queryKey: queryKeys.departments.list(),
        queryFn: () => departmentService.list(),
        staleTime: 60 * 60_000,
    });
    return useMemo(() => {
        const names = new Map((query.data ?? []).map((d) => [d.id, d.name]));
        return (id: string | null) => (id === null ? null : names.get(id) ?? null);
    }, [query.data]);
}
