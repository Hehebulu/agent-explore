package com.example.customeragent.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.customeragent.model.Session;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SessionRepository extends BaseMapper<Session> {
}
