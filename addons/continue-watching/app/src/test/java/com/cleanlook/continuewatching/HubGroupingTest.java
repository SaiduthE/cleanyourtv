package com.cleanlook.continuewatching;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class HubGroupingTest {
    private static Item item(String id, String pkg, String title, long upd) {
        Item it = new Item();
        it.id = id;
        it.pkg = pkg;
        it.title = title;
        it.kind = Item.RESUME;
        it.updatedMs = upd;
        return it;
    }

    private static List<HubGrouping.Section> group(List<Item> in) {
        return HubGrouping.group(Merger.merge(in), HubGrouping.MAX_SECTIONS, HubGrouping.MAX_PER_ROW);
    }

    @Test public void appsAreOrderedByTheirMostRecentWatch() {
        List<Item> in = new ArrayList<>();
        in.add(item("1", "a.plex", "A", 100));
        in.add(item("2", "b.netflix", "B", 900));
        in.add(item("3", "a.plex", "C", 200));
        List<HubGrouping.Section> s = group(in);
        assertEquals(2, s.size());
        assertEquals("b.netflix", s.get(0).pkg);
        assertEquals("a.plex", s.get(1).pkg);
        assertEquals(200, s.get(1).newest);
    }

    @Test public void appsWithoutTimesFollowTheOnesWithTimesThenTheBiggerRowFirst() {
        List<Item> in = new ArrayList<>();
        in.add(item("1", "x.one", "A", 0));
        in.add(item("2", "y.many", "B", 0));
        in.add(item("3", "y.many", "C", 0));
        in.add(item("4", "z.timed", "D", 5));
        List<HubGrouping.Section> s = group(in);
        assertEquals("z.timed", s.get(0).pkg);
        assertEquals("y.many", s.get(1).pkg);
        assertEquals("x.one", s.get(2).pkg);
    }

    @Test public void sameRecencyFallsBackToItemCount() {
        List<Item> in = new ArrayList<>();
        in.add(item("1", "a.one", "A", 50));
        in.add(item("2", "b.two", "B", 50));
        in.add(item("3", "b.two", "C", 40));
        List<HubGrouping.Section> s = group(in);
        assertEquals("b.two", s.get(0).pkg);
        assertEquals("a.one", s.get(1).pkg);
    }

    @Test public void itemsInARowKeepTheMergedOrder() {
        List<Item> in = new ArrayList<>();
        in.add(item("1", "a", "Old", 10));
        in.add(item("2", "a", "New", 30));
        in.add(item("3", "a", "Mid", 20));
        List<Merger.Entry> merged = Merger.merge(in);
        List<HubGrouping.Section> s = HubGrouping.group(merged, 8, 20);
        assertEquals(1, s.size());
        assertEquals(merged, s.get(0).entries);
        assertEquals("New", s.get(0).entries.get(0).primary.title);
    }

    @Test public void noEntriesMeansNoSections() {
        assertTrue(group(new ArrayList<Item>()).isEmpty());
        assertEquals(0, HubGrouping.totalEntries(group(new ArrayList<Item>())));
    }

    @Test public void rowsAndSectionsAreCapped() {
        List<Item> in = new ArrayList<>();
        for (int i = 0; i < 30; i++) in.add(item("a" + i, "many", "T" + i, 1000 - i));
        for (int i = 0; i < 12; i++) in.add(item("p" + i, "app" + i, "P" + i, 10 + i));
        List<HubGrouping.Section> s = group(in);
        assertEquals(HubGrouping.MAX_SECTIONS, s.size());
        assertEquals("many", s.get(0).pkg);
        assertEquals(HubGrouping.MAX_PER_ROW, s.get(0).entries.size());
    }

    @Test public void theRowLabelIsTheMostCommonChannel() {
        Item a = item("1", "p", "A", 3);
        a.channel = "Continue watching";
        Item b = item("2", "p", "B", 2);
        b.channel = "Recommended";
        Item c = item("3", "p", "C", 1);
        c.channel = "Continue watching";
        List<Item> in = new ArrayList<>();
        in.add(a);
        in.add(b);
        in.add(c);
        assertEquals("Continue watching", group(in).get(0).label);
        List<Item> none = new ArrayList<>();
        none.add(item("4", "q", "D", 1));
        assertEquals("", group(none).get(0).label);
    }

    @Test public void columnsAreHeldInsideTheRow() {
        assertEquals(2, HubGrouping.clampColumn(2, 5));
        assertEquals(1, HubGrouping.clampColumn(4, 2));
        assertEquals(0, HubGrouping.clampColumn(-3, 2));
        assertEquals(-1, HubGrouping.clampColumn(0, 0));
    }

    @Test public void sectionStepsStopAtTheEnds() {
        assertEquals(1, HubGrouping.sectionAt(0, 1, 3));
        assertEquals(-1, HubGrouping.sectionAt(0, -1, 3));
        assertEquals(-1, HubGrouping.sectionAt(2, 1, 3));
    }

    @Test public void theWantedColumnSurvivesAShortRow() {
        HubGrouping.Columns c = new HubGrouping.Columns();
        c.moved("a", 4);
        assertEquals("short row: last card", 1, c.landing(2));
        c.landed("b", 1);
        assertEquals("back to a long row: where we started", 4, c.landing(9));
        assertEquals("b remembers its own column", 1, c.remembered("b", 2));
        assertEquals("a never-visited section starts at 0", 0, c.remembered("z", 5));
        assertEquals("a shrunken row holds the memory inside", 1, c.remembered("a", 2));
        c.moved("b", 0);
        assertEquals(0, c.landing(9));
        c.reset();
        assertEquals(0, c.landing(9));
    }
}
