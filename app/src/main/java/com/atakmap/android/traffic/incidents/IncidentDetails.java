package com.atakmap.android.traffic.incidents;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.dropdown.DropDown.OnStateListener;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.traffic.plugin.R;
import com.atakmap.coremap.log.Log;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The page a tapped incident opens: what it is, where, when, and the agency's own
 * notes, newest first.
 *
 * <p>The incident is found by its own id in what the feed has on the map now, never by
 * feature id: a feature is replaced when an incident changes kind, and a tap made
 * before that must still find it. The id is put on the tapped item when it is made
 * (see IncidentOverlay).
 */
public class IncidentDetails extends DropDownReceiver implements OnStateListener,
        IncidentFeed.Listener {

    private static final String TAG = "Traffic511";
    public static final String ACTION = "com.atakmap.android.traffic.INCIDENT_DETAILS";

    private final IncidentFeed feed;
    private final Context pluginContext;
    private final View view;
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.US);
    private final LedSignView sign;
    /** The incident this page is showing while it is open; null when closed. */
    private String shownId;
    private long clearedAt;

    public IncidentDetails(MapView mapView, Context pluginContext, IncidentFeed feed) {
        super(mapView);
        this.feed = feed;
        this.pluginContext = pluginContext;
        this.view = PluginLayoutInflater.inflate(pluginContext, R.layout.incident_details, null);
        this.sign = new LedSignView(pluginContext);
        ((android.view.ViewGroup) view.findViewById(R.id.incident_sign)).addView(sign,
                new android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        view.findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeDropDown();
            }
        });
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final String uid = intent.getStringExtra("targetUID");
        final MapItem item = uid == null ? null
                : getMapView().getRootGroup().deepFindItem("uid", uid);
        String id = item == null ? null : item.getMetaString(IncidentOverlay.META_ID, null);
        if (id == null)
            id = feed.overlay().incidentIdForUid(uid);
        final Incident in = feed.find(id);
        if (in == null) {
            Log.d(TAG, "details: item=" + (item != null) + " id=" + id + " not current");
            // Cleared between the tap and a poll that dropped it.
            Toast.makeText(getMapView().getContext(), "That incident has cleared",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        shownId = in.id;
        clearedAt = 0L;
        show(in);
        showDropDown(view, HALF_WIDTH, FULL_HEIGHT, FULL_WIDTH, HALF_HEIGHT, this);
    }

    /**
     * Every poll, while the page is open: redraw it from the new data, so the notes,
     * the unit status and the "min ago" times move on their own. A KML popup was a
     * snapshot, and the only way to see a new note was to close it and tap again
     * (operator, 2026-09-26).
     *
     * <p>An incident that leaves the feed keeps its page, marked cleared, rather than
     * vanishing under the reader's thumb.
     */
    @Override
    public void onFeedChanged(IncidentFeed f) {
        // Ask ATAK whether the page is up, rather than trusting onDropDownClose to
        // have cleared shownId: opening Details while a Details page is already open
        // makes ATAK close the old one and call onDropDownClose AFTER onReceive has
        // set the new incident, which wiped it, and the page stopped updating
        // (s10-dev-2, 2026-09-26: "12:23 At scene" never reached an open page).
        if (shownId == null || !f.isOn() || isClosed())
            return;
        final Incident in = f.find(shownId);
        if (in != null) {
            clearedAt = 0L;
            show(in);
            return;
        }
        if (clearedAt == 0L)
            clearedAt = System.currentTimeMillis();
        final TextView status = view.findViewById(R.id.incident_status);
        status.setVisibility(View.VISIBLE);
        status.setTextColor(0xFFFF5B52);
        status.setText("Cleared: no longer reported as of " + clock.format(new Date(clearedAt)));
    }

    private void show(Incident in) {
        final TextView tag = view.findViewById(R.id.incident_kind);
        tag.setText(IncidentStyles.setName(in.kind));
        tag.setTextColor(IncidentStyles.color(in.kind));

        final boolean isSign = Incident.SIGN.equals(in.kind);
        // A sign's headline is its own message, which the lit box below shows as the
        // sign lays it out; saying it twice helps nobody.
        ((TextView) view.findViewById(R.id.incident_title)).setText(
                isSign ? "Message sign" : in.title());
        setOrHide(R.id.incident_where, in.where);
        view.findViewById(R.id.incident_sign).setVisibility(isSign ? View.VISIBLE : View.GONE);
        if (isSign)
            sign.setMessage(in.details);

        final StringBuilder when = new StringBuilder();
        final Date reported = parse(in.reported);
        final Date updated = parse(in.updated);
        if (reported != null)
            when.append("Reported ").append(clock.format(reported))
                    .append(" (").append(ago(reported)).append(')');
        if (updated != null && (reported == null || updated.getTime() - reported.getTime() >= 60_000L)) {
            if (when.length() > 0)
                when.append('\n');
            when.append("Updated ").append(clock.format(updated))
                    .append(" (").append(ago(updated)).append(')');
        }
        setOrHide(R.id.incident_when, when.toString());
        setOrHide(R.id.incident_status, in.status);
        ((TextView) view.findViewById(R.id.incident_status)).setTextColor(
                pluginContext.getResources().getColor(R.color.heading_yellow));

        setOrHide(R.id.incident_units_heading, in.units.isEmpty() ? "" : "x");
        setOrHide(R.id.incident_units, in.units);

        final String notes = isSign ? "" : in.details;
        setOrHide(R.id.incident_details_heading, notes.isEmpty() ? "" : "x");
        setOrHide(R.id.incident_details, notes);

        final StringBuilder src = new StringBuilder();
        if (!in.agency.isEmpty())
            src.append("Reported by ").append(in.agency);
        if (!in.area.isEmpty())
            src.append(src.length() > 0 ? ", " : "").append(in.area);
        if (!notes.isEmpty())
            src.append(src.length() > 0 ? ". " : "").append("Note times are the agency's local time.");
        setOrHide(R.id.incident_source, src.toString());
    }

    private void setOrHide(int id, String text) {
        final TextView t = view.findViewById(id);
        if (text == null || text.isEmpty()) {
            t.setVisibility(View.GONE);
            return;
        }
        t.setVisibility(View.VISIBLE);
        if (id != R.id.incident_details_heading && id != R.id.incident_units_heading)
            t.setText(text);
    }

    private static Date parse(String iso) {
        if (iso == null || iso.isEmpty())
            return null;
        final SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        try {
            return f.parse(iso);
        } catch (ParseException e) {
            return null;
        }
    }

    private static String ago(Date d) {
        final long min = Math.max(0L, (System.currentTimeMillis() - d.getTime()) / 60_000L);
        if (min < 1)
            return "just now";
        if (min < 60)
            return min + " min ago";
        final long h = min / 60;
        return h < 48 ? h + " h " + (min % 60) + " min ago" : (h / 24) + " days ago";
    }

    @Override
    public void onDropDownSelectionRemoved() {
    }

    @Override
    public void onDropDownVisible(boolean v) {
    }

    @Override
    public void onDropDownSizeChanged(double width, double height) {
    }

    /**
     * Plugin stop: take the page down. Left open, it stays on screen wired to the
     * unloaded plugin and never updates again, which reads as a broken page
     * (s10-dev-2, 2026-09-26, after a reinstall with Details open).
     */
    public void closeIfOpen() {
        try {
            if (!isClosed())
                closeDropDown();
        } catch (RuntimeException e) {
            Log.w(TAG, "could not close details: " + e);
        }
    }

    @Override
    public void onDropDownClose() {
        // Nothing to clear: onFeedChanged asks isClosed(). This callback also fires
        // when one Details page replaces another, after the new one is already set.
    }

    @Override
    protected void disposeImpl() {
    }
}
