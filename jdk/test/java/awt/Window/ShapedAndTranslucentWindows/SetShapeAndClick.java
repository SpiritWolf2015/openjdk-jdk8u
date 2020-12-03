/*
 * Copyright (c) 2010, 2014, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Area;
import java.awt.geom.GeneralPath;
import java.awt.geom.Rectangle2D;

/*
 * @test
 * @summary Check if a window set with shape clips the contents
 *
 * Test Description: Check if PERPIXEL_TRANSPARENT Translucency type is supported
 *      by the current platform. Proceed if it is supported. Apply different types
 *      of shapes on a Window which contains some awt components. Shape should be
 *      applied in such a way that some components are partially clipped off. Check
 *      if the components appear only partially and events work correctly for those
 *      components - i.e. events occur only on the areas which appear and do not
 *      occur on the clipped off areas. Events should be checked by clicking on the
 *      visible and clipped regions. Repeat this for Window, Dialog and Frame.
 * Expected Result: If PERPIXEL_TRANSPARENT Translucency type is supported, clicking
 *      on clipped region should deliver the event to the background (it should be
 *      another Window behind the test window)
 *
 * @author mrkam
 * @author Dmitriy Ermashov (dmitriy.ermashov@oracle.com)
 * @library ../../../../lib/testlibrary
 * @build Common ExtendedRobot
 * @run main SetShapeAndClick
 */

public class SetShapeAndClick extends Common {

    Component south, center, north;
    volatile int clicked;

    public static void main(String[] args) throws Exception {
        if (checkTranslucencyMode(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSPARENT))
            for (Class<Window> windowClass: WINDOWS_TO_TEST)
                new SetShapeAndClick(windowClass).doTest();
    }

    public SetShapeAndClick(Class windowClass) throws Exception {
        super(windowClass);
    }

    @Override
    public void initBackgroundFrame() {
        super.initBackgroundFrame();
        background.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                clicked |= 1 << 0;
            }
        });
    }

    @Override
    public void initGUI() {
        if (windowClass.equals(Frame.class)) {
            window = new Frame();
            ((Frame) window).setUndecorated(true);
        } else  if (windowClass.equals(Dialog.class)) {
            window = new Dialog(background);
            ((Dialog) window).setUndecorated(true);
        } else {
            window = new Window(background);
        }

        window.setLocation(2 * dl, 2 * dl);
        window.setPreferredSize(new Dimension(200, 200));
        window.setLayout(new BorderLayout());

        window.addComponentListener(new ComponentAdapter() {
            public void componentResized(ComponentEvent e) {
                applyShape();
            }
        });

        south = new Label("South");
        south.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                clicked |= 1 << 3;
            }
        });
        window.add(south, BorderLayout.SOUTH);

        center = new List(5);
        center.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                clicked |= 1 << 2;
            }
        });
        window.add(center, BorderLayout.CENTER);

        north = new TextField("North");
        north.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                clicked |= 1 << 1;
            }
        });
        window.add(north, BorderLayout.NORTH);

        window.pack();
        window.setVisible(true);
        window.toFront();

        System.out.println("Checking " + window.getClass().getName() + "...");
    }

    @Override
    public void doTest() throws Exception {

        robot.waitForIdle();

        Point wls = window.getLocationOnScreen();
        Point ls;
        int y;
        ls = north.getLocationOnScreen();
        checkClick(ls.x + north.getWidth() / 3, ls.y + north.getHeight() / 2, 1);

        ls = center.getLocationOnScreen();
        checkClick(ls.x + center.getWidth() * 3 / 4, ls.y + center.getHeight() * 3 / 4, 2);

        ls = south.getLocationOnScreen();
        checkClick(ls.x + south.getWidth() * 2 / 3, ls.y + south.getHeight() / 2, 3);

        ls = center.getLocationOnScreen();
        checkClick(ls.x + center.getWidth() / 4, ls.y + center.getHeight() / 4, 2);

        ls = north.getLocationOnScreen();
        y = ls.y + north.getHeight() / 2;
        checkClick(wls.x + 200 - (y - wls.y), y, 0);

        EventQueue.invokeAndWait(window::toFront);
        robot.waitForIdle();

        ls = center.getLocationOnScreen();
        y = ls.y + center.getHeight() / 2;
        checkClick(wls.x + 200 - (y - wls.y), y, 0);

        EventQueue.invokeAndWait(window::toFront);
        robot.waitForIdle();

        ls = south.getLocationOnScreen();
        y = ls.y + south.getHeight() / 2;
        checkClick(wls.x + 200 - (y - wls.y), y, 0);

        EventQueue.invokeAndWait(window::dispose);
        EventQueue.invokeAndWait(background::dispose);

        robot.waitForIdle();
    }

    @Override
    public void applyShape() {
        Area shape = new Area(new Rectangle2D.Float(0, 0, 200, 200));
        GeneralPath gp;
        gp = new GeneralPath();
        gp.moveTo(190, 0);
        gp.lineTo(200, 0);
        gp.lineTo(200, 10);
        gp.lineTo(10, 200);
        gp.lineTo(0, 200);
        gp.lineTo(0, 190);
        gp.closePath();
        shape.subtract(new Area(gp));

        window.setShape(shape);
    }

    void checkClick(int x, int y, int flag) throws Exception {

        System.out.println("Trying to click point " + x + ", " + y + ", looking for " + flag + " flag to trigger.");

        clicked = 0;
        robot.mouseMove(x, y);
        robot.click();

        for (int i = 0; i < 100; i++)
            if ((clicked & (1 << flag)) == 0)
                robot.delay(50);
            else
                break;

        if ((clicked & (1 << flag)) == 0)
            throw new RuntimeException("FAIL: Flag " + flag + " is not triggered for point " + x + ", " + y + "!");
    }
}
