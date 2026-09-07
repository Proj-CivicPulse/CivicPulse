import { useCallback, useMemo, useRef, useState } from 'react';
import { ChevronDown, ChevronLeft, ChevronRight, ChevronUp } from 'lucide-react';
import type { KeyboardEvent, ReactNode } from 'react';
import styles from './DataTable.module.css';

export interface Column<T> {
    key: string;
    header: string;
    cell: (row: T) => ReactNode;
    /** Supplying this makes the column sortable. */
    sortValue?: (row: T) => string | number;
    align?: 'start' | 'end';
    /** Any CSS width, e.g. '120px' or 'minmax(0, 1fr)'. */
    width?: string;
}

interface Props<T> {
    columns: readonly Column<T>[];
    rows: readonly T[];
    rowKey: (row: T) => string;
    /** Controlled selection, so the map and the queue stay two views of one set. */
    selectedId?: string | null;
    onSelect?: (row: T) => void;
    /** Required: a table needs an accessible name. Visually hidden. */
    caption: string;
    emptyMessage?: string;
    initialSort?: { key: string; direction: SortDirection };
    /**
     * Rows per page. Omit to render every row.
     *
     * Paging is client-side: the incidents endpoint returns the whole filtered
     * set and there is no server-side paging in the contract. At the scale a
     * ward officer actually works with that is the right trade — sorting stays
     * over the FULL set rather than only the visible page, which is what makes
     * "most urgent first" mean anything.
     */
    pageSize?: number;
}

type SortDirection = 'asc' | 'desc';

/**
 * Dense officer-console table: 36px rows, 1px rules, no zebra striping, sticky
 * header, sort indicated by a chevron rather than a coloured header.
 *
 * role="grid" (rather than a plain table) is what makes aria-selected valid on
 * a row and gives the roving-tabindex keyboard model its expected semantics.
 */
export default function DataTable<T>({
    columns,
    rows,
    rowKey,
    selectedId,
    onSelect,
    caption,
    emptyMessage = 'Nothing to show.',
    initialSort,
    pageSize,
}: Props<T>) {
    const [sort, setSort] = useState<{ key: string; direction: SortDirection } | null>(
        initialSort ?? null
    );
    const [focusedIndex, setFocusedIndex] = useState(0);
    const [page, setPage] = useState(0);
    const bodyRef = useRef<HTMLTableSectionElement>(null);

    const sortedRows = useMemo(() => {
        if (!sort) return rows;
        const column = columns.find((c) => c.key === sort.key);
        if (!column?.sortValue) return rows;
        const { sortValue } = column;

        return [...rows].sort((a, b) => {
            const av = sortValue(a);
            const bv = sortValue(b);
            let result: number;
            if (typeof av === 'number' && typeof bv === 'number') {
                result = av - bv;
            } else {
                result = String(av).localeCompare(String(bv));
            }
            return sort.direction === 'asc' ? result : -result;
        });
    }, [rows, columns, sort]);

    const pageCount = pageSize ? Math.max(1, Math.ceil(sortedRows.length / pageSize)) : 1;
    // Filtering can drop the row count below the current page. Clamp rather
    // than render an empty page the user has no obvious way back from.
    const safePage = Math.min(page, pageCount - 1);
    const visibleRows = useMemo(
        () =>
            pageSize
                ? sortedRows.slice(safePage * pageSize, safePage * pageSize + pageSize)
                : sortedRows,
        [sortedRows, pageSize, safePage]
    );

    const goToPage = useCallback((next: number) => {
        setPage(next);
        // Focus belongs on the first row of the page just revealed; leaving it
        // on an index that no longer exists strands keyboard navigation.
        setFocusedIndex(0);
    }, []);

    const toggleSort = useCallback((key: string) => {
        // A new sort order makes the current page number meaningless.
        setPage(0);
        setFocusedIndex(0);
        setSort((current) => {
            if (current?.key !== key) return { key, direction: 'asc' };
            if (current.direction === 'asc') return { key, direction: 'desc' };
            // Third click clears the sort and restores the server's ordering,
            // which for incidents is already priority-first.
            return null;
        });
    }, []);

    const focusRow = useCallback((index: number) => {
        setFocusedIndex(index);
        const row = bodyRef.current?.children[index];
        if (row instanceof HTMLElement) row.focus();
    }, []);

    const onKeyDown = useCallback(
        (event: KeyboardEvent<HTMLTableRowElement>, index: number, row: T) => {
            switch (event.key) {
                case 'ArrowDown':
                    event.preventDefault();
                    focusRow(Math.min(index + 1, visibleRows.length - 1));
                    break;
                case 'ArrowUp':
                    event.preventDefault();
                    focusRow(Math.max(index - 1, 0));
                    break;
                case 'Home':
                    event.preventDefault();
                    focusRow(0);
                    break;
                case 'End':
                    event.preventDefault();
                    focusRow(visibleRows.length - 1);
                    break;
                case 'Enter':
                case ' ':
                    event.preventDefault();
                    onSelect?.(row);
                    break;
                default:
                    break;
            }
        },
        [focusRow, onSelect, visibleRows.length]
    );

    if (rows.length === 0) {
        return <p className={styles.empty}>{emptyMessage}</p>;
    }

    const firstOnPage = pageSize ? safePage * pageSize + 1 : 1;
    const lastOnPage = pageSize
        ? Math.min(safePage * pageSize + pageSize, sortedRows.length)
        : sortedRows.length;

    return (
        <div className={styles.wrap}>
        {/* Its own scroll container, so a wide table never scrolls the page body. */}
        <div className={styles.scroll}>
            <table className={styles.table} role="grid">
                <caption className="srOnly">{caption}</caption>
                <thead className={styles.head}>
                    <tr>
                        {columns.map((column) => {
                            const isSorted = sort?.key === column.key;
                            const ariaSort = isSorted
                                ? sort.direction === 'asc'
                                    ? 'ascending'
                                    : 'descending'
                                : 'none';

                            return (
                                <th
                                    key={column.key}
                                    scope="col"
                                    aria-sort={column.sortValue ? ariaSort : undefined}
                                    className={column.align === 'end' ? styles.thEnd : styles.th}
                                    style={column.width !== undefined ? { width: column.width } : undefined}
                                >
                                    {column.sortValue ? (
                                        <button
                                            type="button"
                                            className={styles.sortButton}
                                            onClick={() => toggleSort(column.key)}
                                        >
                                            {column.header}
                                            <span
                                                className={isSorted ? styles.chevronActive : styles.chevron}
                                                aria-hidden="true"
                                            >
                                                {isSorted && sort.direction === 'desc' ? (
                                                    <ChevronDown size={14} strokeWidth={2} />
                                                ) : (
                                                    <ChevronUp size={14} strokeWidth={2} />
                                                )}
                                            </span>
                                        </button>
                                    ) : (
                                        column.header
                                    )}
                                </th>
                            );
                        })}
                    </tr>
                </thead>

                <tbody ref={bodyRef}>
                    {visibleRows.map((row, index) => {
                        const key = rowKey(row);
                        const isSelected = selectedId !== undefined && selectedId === key;

                        return (
                            <tr
                                key={key}
                                className={isSelected ? styles.rowSelected : styles.row}
                                aria-selected={isSelected}
                                // Roving tabindex: exactly one row is in the tab
                                // order, and the arrow keys move within the grid.
                                tabIndex={index === focusedIndex ? 0 : -1}
                                onClick={() => onSelect?.(row)}
                                onFocus={() => setFocusedIndex(index)}
                                onKeyDown={(event) => onKeyDown(event, index, row)}
                            >
                                {columns.map((column) => (
                                    <td
                                        key={column.key}
                                        role="gridcell"
                                        className={column.align === 'end' ? styles.tdEnd : styles.td}
                                    >
                                        {column.cell(row)}
                                    </td>
                                ))}
                            </tr>
                        );
                    })}
                </tbody>
            </table>
        </div>

        {pageSize !== undefined && pageCount > 1 && (
            <nav className={styles.pager} aria-label="Table pages">
                {/*
                  * A live region, so a screen reader hears the range change on
                  * page turn — the table body itself gives no such announcement.
                  */}
                <span className={styles.pageStatus} aria-live="polite">
                    {firstOnPage}&ndash;{lastOnPage} of {sortedRows.length}
                </span>

                <div className={styles.pageButtons}>
                    <button
                        type="button"
                        className={styles.pageButton}
                        onClick={() => goToPage(safePage - 1)}
                        disabled={safePage === 0}
                        aria-label="Previous page"
                    >
                        <ChevronLeft size={16} strokeWidth={2} aria-hidden="true" />
                    </button>

                    <span className={styles.pageOf}>
                        Page {safePage + 1} of {pageCount}
                    </span>

                    <button
                        type="button"
                        className={styles.pageButton}
                        onClick={() => goToPage(safePage + 1)}
                        disabled={safePage >= pageCount - 1}
                        aria-label="Next page"
                    >
                        <ChevronRight size={16} strokeWidth={2} aria-hidden="true" />
                    </button>
                </div>
            </nav>
        )}
        </div>
    );
}
