// Stable dropdown-only frontend (no inline grey prediction)
// Adds robust "Did you mean" UI + keyboard handling

const searchInput = document.getElementById("searchInput");
const suggestionsList = document.getElementById("suggestionsList");
const debug = document.getElementById("debug");
const trendingList = document.getElementById("trendingList");

let debounceTimer = null;
const DEBOUNCE_MS = 160;
let selectedIndex = -1;
let suggestions = [];

// ---------------- Helpers ----------------
function hideSuggestions() {
    suggestionsList.innerHTML = "";
    suggestionsList.hidden = true;
    selectedIndex = -1;
}

function showDebug(msg) {
    if (!debug) return;
    debug.hidden = false;
    debug.textContent = msg;
}

// Build prefix and context for the current input.
// Returns { prefix, context } where prefix is the current token after last space.
function buildPrefixContext(full) {
    const trailingRemoved = full.replace(/\s+$/, ""); // remove trailing spaces for token detection
    const lastSpace = trailingRemoved.lastIndexOf(" ");
    if (lastSpace === -1) {
        return { prefix: trailingRemoved, context: "" };
    } else {
        const prefix = trailingRemoved.slice(lastSpace + 1);
        const left = trailingRemoved.slice(0, lastSpace).trim();
        const parts = left ? left.split(/\s+/) : [];
        const ctx = parts.slice(Math.max(0, parts.length - 2)).join(" ");
        return { prefix, context: ctx };
    }
}

// Accept a suggestion: replace either the current token or the left context (if phrase)
function acceptSuggestion(selected) {
    const full = searchInput.value;
    const { prefix, context } = buildPrefixContext(full);
    const sel = selected.trim();

    // If suggestion is a phrase and begins with the context, replace left-side with phrase
    if (sel.includes(" ") && context) {
        const lowerSel = sel.toLowerCase();
        const lowerContext = context.toLowerCase();
        if (lowerSel.startsWith(lowerContext)) {
            // Replace everything up to the start of current prefix with the phrase
            const leftPart = full.slice(0, full.lastIndexOf(prefix)); // preserve any leading whitespace positions
            searchInput.value = (leftPart + sel).trim() + " ";
            hideSuggestions();
            fetchSuggestionsAfterInsert(searchInput.value);
            return;
        }
    }

    // Otherwise replace only the current prefix token
    if (prefix === "") {
        // At a space. Append selection.
        searchInput.value = (full + sel + " ").replace(/\s+/g, " ").trimLeft();
    } else {
        const trimmedEnd = full.replace(/\s+$/, "");
        const lastSpace = trimmedEnd.lastIndexOf(" ");
        const start = lastSpace === -1 ? 0 : lastSpace + 1;
        const left = full.substring(0, start);
        searchInput.value = (left + sel + " ").replace(/\s+/g, " ").trimLeft();
    }

    hideSuggestions();
    // report acceptance to backend (personalization)
    postAccept(sel);
    fetchSuggestionsAfterInsert(searchInput.value);
}

function fetchSuggestionsAfterInsert(newFullValue) {
    const trimmed = newFullValue.trim();
    const parts = trimmed ? trimmed.split(/\s+/) : [];
    const ctx = parts.slice(Math.max(0, parts.length - 2)).join(" ");
    // prefix empty because we appended a trailing space
    fetchSuggestions("", ctx);
}

function postAccept(selected) {
    const userId = localStorage.getItem('demoUserId') || '';
    fetch('/api/accept', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({ userId: userId, selected: selected })
    }).catch(() => {});
}

// ---------------- Fetch + Render ----------------
async function fetchSuggestions(prefix, context) {
    try {
        const limit = 6;
        const resp = await fetch(`/api/suggest?q=${encodeURIComponent(prefix)}&context=${encodeURIComponent(context)}&limit=${limit}`);
        if (!resp.ok) {
            hideSuggestions();
            showDebug(`HTTP ${resp.status}`);
            return;
        }
        const body = await resp.json();
        const items = Array.isArray(body?.suggestions) ? body.suggestions : [];
        suggestions = items.map(s => (typeof s === "string") ? s : (s.text ?? s.word ?? "")).filter(Boolean);

        // Get Did You Mean suggestion
        const dym = body?.didYouMean ?? body?.didyoumean ?? null;

        displaySuggestions(suggestions, prefix, dym);
        showDebug(`took ${body?.meta?.tookMs ?? "?"} ms • ${suggestions.length}`);
    } catch (err) {
        console.error("fetch error", err);
        hideSuggestions();
    }
}

function insertDidYouMeanRow(replacement) {
    // remove existing didyoumean row if any
    const existing = suggestionsList.querySelector("li.didyoumean");
    if (existing) existing.remove();

    const hintLi = document.createElement("li");
    hintLi.className = "didyoumean";
    hintLi.tabIndex = 0;
    hintLi.dataset.value = replacement;
    hintLi.innerHTML = `Did you mean: <strong>${escapeHtml(replacement)}</strong>? (click or press Enter)`;
    hintLi.addEventListener("click", () => {
        applyDidYouMean(replacement);
        postAccept(replacement);
    });
    hintLi.addEventListener("keydown", (ev) => {
        if (ev.key === "Enter" || ev.key === " ") {
            ev.preventDefault();
            hintLi.click();
        }
    });

    // insert at top
    suggestionsList.insertBefore(hintLi, suggestionsList.firstChild);
}

// Apply replacement into input and fetch next suggestions
function applyDidYouMean(replacement) {
    const full = searchInput.value;
    const trimmedEnd = full.replace(/\s+$/, '');
    const lastSpace = trimmedEnd.lastIndexOf(' ');
    if (lastSpace === -1) {
        searchInput.value = replacement + ' ';
    } else {
        const left = full.substring(0, lastSpace + 1); // include trailing space
        searchInput.value = left + replacement + ' ';
    }
    const parts = searchInput.value.trim().split(/\s+/);
    const newContext = parts.slice(Math.max(0, parts.length - 2)).join(' ');
    fetchSuggestions('', newContext);
    hideSuggestions();
}

function displaySuggestions(words, prefix, didYouMean) {
    suggestionsList.innerHTML = "";
    if (didYouMean) {
        const dymLi = document.createElement("li");
        dymLi.className = "didyoumean";
        dymLi.innerHTML = `Did you mean: <strong>${escapeHtml(didYouMean)}</strong>?`;
        dymLi.addEventListener("click", () => {
            searchInput.value = didYouMean + " ";
            hideSuggestions();
            fetchSuggestionsAfterInsert(searchInput.value);
        });
        suggestionsList.appendChild(dymLi);
    }

    if (!words || words.length === 0) {
        suggestionsList.hidden = !didYouMean;
        return;
    }

    const lowerPrefix = (prefix || "").toLowerCase();
    words.forEach((w, idx) => {
        const li = document.createElement("li");
        li.tabIndex = 0;
        if (lowerPrefix && w.toLowerCase().startsWith(lowerPrefix)) {
            li.innerHTML = `<strong>${escapeHtml(w.slice(0, lowerPrefix.length))}</strong>${escapeHtml(w.slice(lowerPrefix.length))}`;
        } else {
            li.textContent = w;
        }
        li.dataset.index = idx;
        li.dataset.value = w;
        li.addEventListener("click", () => acceptSuggestion(w));
        suggestionsList.appendChild(li);
    });
    selectedIndex = -1;
    suggestionsList.hidden = false;
}

// ---------------- Keys + Input ----------------
searchInput.addEventListener("input", (e) => {
    clearTimeout(debounceTimer);
    const full = e.target.value;
    if (!full || full.trim().length === 0) {
        hideSuggestions();
        showTrending(); // show trending when input empty
        return;
    }
    debounceTimer = setTimeout(() => {
        const { prefix, context } = buildPrefixContext(full);
        fetchSuggestions(prefix.trim(), context.trim());
    }, DEBOUNCE_MS);
});

searchInput.addEventListener("keydown", (e) => {
    const lis = [...document.querySelectorAll(".suggestions li")];
    const count = lis.length;

    // Arrow navigation
    if (e.key === "ArrowDown" || e.key === "ArrowUp") {
        if (count === 0) return;
        e.preventDefault();
        selectedIndex = (e.key === "ArrowDown")
            ? (selectedIndex + 1 + count) % count
            : (selectedIndex - 1 + count) % count;
        lis.forEach((li, i) => li.classList.toggle("active", i === selectedIndex));
        if (selectedIndex >= 0 && lis[selectedIndex]) lis[selectedIndex].scrollIntoView({ block: "nearest" });
        return;
    }

    if (e.key === "Enter") {
        // if arrow selected item, accept it
        if (selectedIndex >= 0 && selectedIndex < count) {
            e.preventDefault();
            const chosen = lis[selectedIndex].dataset.value;
            if (chosen) acceptSuggestion(chosen);
            return;
        }

        // else, if did-you-mean row exists, accept it
        const didRow = suggestionsList.querySelector("li.didyoumean");
        if (didRow) {
            e.preventDefault();
            const val = didRow.dataset.value || didRow.textContent;
            if (val) {
                applyDidYouMean(val.trim());
                postAccept(val.trim());
            }
            return;
        }

        // else no selection and no didyoumean -> do nothing special (let form submit if any)
        return;
    }

    if (e.key === "Escape") {
        hideSuggestions();
        return;
    }
});

// click outside to hide
document.addEventListener("click", (e) => {
    if (!e.target.closest(".predictive-box") && !e.target.closest(".suggestions")) {
        hideSuggestions();
    }
});

function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, ch => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch]));
}

// ---------------- Trending ----------------
async function showTrending() {
    try {
        // request backend for suggestions with empty prefix/context
        const userId = localStorage.getItem('demoUserId') || '';
        const resp = await fetch(`/api/suggest?q=&context=&limit=12&userId=${encodeURIComponent(userId)}`, { headers: { 'Accept': 'application/json' } });
        if (!resp.ok) {
            console.warn('Trending fetch failed status', resp.status);
            renderTrendingFallback();
            return;
        }

        const body = await resp.json();
        const items = Array.isArray(body?.suggestions) ? body.suggestions : [];

        trendingList.innerHTML = '';

        // Normalize each item to a display string
        const normalized = items
            .map(s => (typeof s === 'string') ? s : (s.text ?? s.word ?? s.getText ?? ''))
            .filter(Boolean);

        if (normalized.length === 0) {
            renderTrendingFallback();
            return;
        }

        // show up to 12 items
        normalized.slice(0, 12).forEach(text => {
            const li = document.createElement('li');
            li.textContent = text;
            li.addEventListener('click', () => {
                // clicking trending inserts the phrase and requests next suggestions
                searchInput.value = text + ' ';
                fetchSuggestions('', text);
            });
            trendingList.appendChild(li);
        });

    } catch (err) {
        console.warn('trending fetch error', err);
        renderTrendingFallback();
    }
}
function renderTrendingFallback() {
    trendingList.innerHTML = '';
    const fallback = ['the', 'to', 'is', 'it', 'you', 'i', 'a', 'for', 'in', 'on', 'and', 'that'];
    fallback.slice(0, 12).forEach(t => {
        const li = document.createElement('li');
        li.textContent = t;
        li.addEventListener('click', () => {
            searchInput.value = t + ' ';
            fetchSuggestions('', t);
        });
        trendingList.appendChild(li);
    });
}

window.addEventListener('DOMContentLoaded', () => {
    // show trending immediately on load
    showTrending();
});
hideSuggestions();
showTrending();
