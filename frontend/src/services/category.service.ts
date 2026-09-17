import { get } from './api';
import { SPRING_API_PATH } from '../config/constants';

/**
 * The canonical category registry, served by backend-spring.
 *
 * This replaces the list the submit form used to carry itself. The frontend is
 * no longer an authority on what a category is: `code` is the only value that
 * may be submitted, and the server rejects anything it cannot resolve, so a
 * second copy of the vocabulary here would drift into submissions the backend
 * refuses.
 */
export interface Category {
    /** The stable identifier to submit. Never rendered. */
    code: string;
    /** Officer-facing canonical name. */
    name: string;
    /** The only field a resident should ever see. */
    displayName: string;
}

/**
 * What the picker falls back to when /categories cannot be reached.
 *
 * A resident who cannot load reference data must still be able to file a
 * report — that is the entire product — so the form degrades to the codes that
 * have been canonical since the registry was seeded rather than showing an
 * empty select. These are codes, not a rival vocabulary: every one of them
 * resolves server-side, so a submission made from this fallback is accepted
 * exactly as one made from the live list.
 *
 * The cost of the fallback is that a category added after this ships is missing
 * from it. That is strictly better than being unable to report at all, and the
 * live list is what is used in every normal case.
 */
export const FALLBACK_CATEGORIES: Category[] = [
    { code: 'pothole', name: 'Pothole / road damage', displayName: 'Pothole or damaged road' },
    { code: 'streetlight', name: 'Street lighting', displayName: 'Street light out' },
    { code: 'garbage', name: 'Solid waste', displayName: 'Garbage not collected' },
    { code: 'water', name: 'Water supply', displayName: 'Water supply or leak' },
    { code: 'drainage', name: 'Drainage', displayName: 'Blocked drain or waterlogging' },
    { code: 'footpath', name: 'Footpath', displayName: 'Damaged or blocked footpath' },
    { code: 'noise', name: 'Noise nuisance', displayName: 'Excessive noise' },
    { code: 'other', name: 'Other', displayName: 'Something else' },
];

export const categoryService = {
    /** Active categories in submit-form order. Public — no session needed. */
    list: () => get<Category[]>(SPRING_API_PATH, '/categories'),
};
