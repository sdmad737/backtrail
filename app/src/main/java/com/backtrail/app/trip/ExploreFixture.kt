package com.backtrail.app.trip

import com.backtrail.app.contracts.*

/** Explicit opt-in replay only. Never loaded by the tracking service or live capture path. */
object ExploreFixture {
    val points = listOf(
        37.5650 to -122.3698,37.5655 to -122.3696,37.5660 to -122.3699,
        37.5664 to -122.3703,37.5668 to -122.3701,37.5672 to -122.3696,
        37.5677 to -122.3697,37.5682 to -122.3702,37.5687 to -122.3706,
        37.5691 to -122.3703,37.5696 to -122.3697,37.5700 to -122.3694,
        37.5704 to -122.3698,37.5708 to -122.3704,37.5712 to -122.3708,
    ).mapIndexed { i,(lat,lon) -> TrailPoint("demo-$i",lat,lon,5f,1_792_800_000_000+i*60_000L,0) }
    fun outing():Outing=Outing("explore","The way you came",points.first().time,OutingPhase.ENDED,points=points,
        memories=listOf(
            Memory("demo-start","Trailhead","The place we started","asset:demo/log-bridge.jpg",points[0].time,points[0],0,"Sample description · trail entrance","Sample answer"),
            Memory("demo-bridge","Stone bridge","Collect my water bottle here on the way back","asset:demo/stone-bridge.jpg",points[5].time,points[5],5,"Sample description · stone arch beside the creek","Sample answer",PickupPhase.ARMED),
            Memory("demo-creek","Redwood creek","A quiet place for a break","asset:demo/redwood-creek.jpg",points[10].time,points[10],10,"Sample description · forest creek beneath tall trees","Sample answer"),
        ))
}
