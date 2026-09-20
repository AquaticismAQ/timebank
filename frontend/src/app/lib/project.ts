/**
 * Facts about the project itself, as opposed to the school running it.
 *
 * Hardcoded on purpose. These are properties of this codebase, not of a
 * deployment, so they do not belong in `school_info` or any other configurable
 * table — a second school installing this would not change them, and giving an
 * administrator a field to edit would only invite the repository link to be
 * pointed somewhere wrong.
 *
 * It lives here rather than in a component because it is now shown in two
 * places — the login footer and System Admin's System Info card — and two
 * copies of a URL is exactly the sort of thing that drifts.
 */
export const PROJECT_REPO_URL = 'https://github.com/AquaticismAQ/timebank';
