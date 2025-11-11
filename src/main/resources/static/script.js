// Stable dropdown-only frontend (Did-You-Mean + trending + arrow nav)

const searchInput = document.getElementById("searchInput");
const suggestionsList = document.getElementById("suggestionsList");
const trendingList = document.getElementById("trendingList");
const debug = document.getElementById("debug");

let debounceTimer = null;
const DEBOUNCE_MS = 160;
let selectedIndex = -1;
let suggestions = [];

function hideSuggestions() {
    suggestionsList.innerHTML = "";
    suggestionsList.hidden = true;
    selectedIndex = -1;
}

function escapeHtml(s){ return String(s).replace(/[&<>"']/g, ch => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch])); }

function buildPrefixContext(full) {
    const trailingRemoved = full.replace(/\s+$/, "");
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

async function fetchSuggestions(prefix, context) {
    try {
        const limit = 6;
        const url = `/api/suggest?q=${encodeURIComponent(prefix)}&context=${encodeURIComponent(context)}&limit=${limit}`;
        const resp = await fetch(url, { headers: { "Accept": "application/json" }});
        if (!resp.ok) { hideSuggestions(); return; }
        const body = await resp.json();

        const dym = body?.didYouMean || body?.didyoumean || body?.correction || null;
        const items = Array.isArray(body?.suggestions) ? body.suggestions : [];
        suggestions = items.map(s => (typeof s === "string") ? s : (s.text ?? s.word ?? "")).filter(Boolean);

        renderSuggestions(suggestions, prefix, dym);
    } catch (e) { console.error(e); hideSuggestions(); }
}

function renderSuggestions(words, prefix, didYouMean) {
    suggestionsList.innerHTML = "";
    if (didYouMean) {
        const li = document.createElement("li");
        li.className = "didyoumean";
        li.tabIndex = 0;
        li.dataset.value = didYouMean;
        li.innerHTML = `Did you mean: <strong>${escapeHtml(didYouMean)}</strong>? (click or Enter)`;
        li.addEventListener("click", () => {
            applyReplacement(didYouMean);
            postAccept(didYouMean);
        });
        li.addEventListener("keydown", (e)=>{ if (e.key === "Enter"){ e.preventDefault(); li.click(); }});
        suggestionsList.appendChild(li);
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
        li.addEventListener("click", () => { applyReplacement(w); postAccept(w); });
        suggestionsList.appendChild(li);
    });
    selectedIndex = -1;
    suggestionsList.hidden = false;
}

function applyReplacement(selected) {
    const full = searchInput.value;
    const trailingRemoved = full.replace(/\s+$/, "");
    const lastSpace = trailingRemoved.lastIndexOf(" ");
    if (lastSpace === -1) {
        searchInput.value = selected + " ";
    } else {
        const left = full.substring(0, lastSpace + 1);
        searchInput.value = left + selected + " ";
    }
    hideSuggestions();
    // fetch next suggestions for new context
    const parts = searchInput.value.trim().split(/\s+/);
    const ctx = parts.slice(Math.max(0, parts.length - 2)).join(" ");
    fetchSuggestions("", ctx);
}

function postAccept(selected) {
    const userId = localStorage.getItem('demoUserId') || '';
    fetch('/api/accept', {
        method: 'POST',
        headers: {'Content-Type':'application/json'},
        body: JSON.stringify({ userId: userId, selected: selected })
    }).catch(()=>{});
}

searchInput.addEventListener("input", (e)=>{
    clearTimeout(debounceTimer);
    const full = e.target.value;
    if (!full || full.trim().length === 0) { hideSuggestions(); showTrending(); return; }
    debounceTimer = setTimeout(() => {
        const { prefix, context } = buildPrefixContext(full);
        fetchSuggestions(prefix.trim(), context.trim());
    }, DEBOUNCE_MS);
});

searchInput.addEventListener("keydown", (e)=>{
    const lis = [...document.querySelectorAll(".suggestions li")];
    const count = lis.length;

    if (e.key === "ArrowDown" || e.key === "ArrowUp") {
        if (count === 0) return;
        e.preventDefault();
        selectedIndex = (e.key === "ArrowDown")
            ? (selectedIndex + 1 + count) % count
            : (selectedIndex - 1 + count) % count;
        lis.forEach((li, i)=> li.classList.toggle("active", i === selectedIndex));
        if (selectedIndex >= 0 && lis[selectedIndex]) lis[selectedIndex].scrollIntoView({ block: "nearest" });
        return;
    }

    if (e.key === "Enter") {
        if (selectedIndex >= 0 && selectedIndex < count) {
            e.preventDefault();
            const chosen = lis[selectedIndex].dataset.value;
            if (chosen) { applyReplacement(chosen); postAccept(chosen); }
            return;
        }
        // if there is a didyoumean row and no selection, accept it
        const did = suggestionsList.querySelector("li.didyoumean");
        if (did) {
            e.preventDefault();
            const v = did.dataset.value || did.textContent;
            if (v) { applyReplacement(v.trim()); postAccept(v.trim()); }
            return;
        }
        return;
    }

    if (e.key === "Escape") { hideSuggestions(); return; }
});

document.addEventListener("click", (e)=>{
    if (!e.target.closest(".predictive-box") && !e.target.closest(".suggestions")) hideSuggestions();
});

// Trending
async function showTrending(){
    try {
        const resp = await fetch(`/api/suggest?q=&context=&limit=12`);
        if (!resp.ok) { renderTrendingFallback(); return; }
        const body = await resp.json();
        const items = Array.isArray(body?.suggestions) ? body.suggestions : [];
        trendingList.innerHTML = "";
        const normalized = items.map(s => (typeof s==="string")?s:(s.text??s.word??"")).filter(Boolean);
        if (normalized.length === 0) { renderTrendingFallback(); return; }
        normalized.slice(0,12).forEach(text=>{
            const li = document.createElement("li");
            li.textContent = text;
            li.addEventListener("click", ()=>{ searchInput.value = text + " "; fetchSuggestions("", text); });
            trendingList.appendChild(li);
        });
    } catch (e) { renderTrendingFallback(); }
}
function renderTrendingFallback(){
    trendingList.innerHTML = "";
    ['the','to','is','it','you','i','a','for','in','on','and','that'].slice(0,12).forEach(t=>{
        const li = document.createElement("li"); li.textContent = t;
        li.addEventListener("click", ()=>{ searchInput.value = t + " "; fetchSuggestions("", t); });
        trendingList.appendChild(li);
    });
}

window.addEventListener('DOMContentLoaded', ()=>{ showTrending(); });
hideSuggestions();
showTrending();
